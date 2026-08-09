package com.zhiban.rebuild.runtime.tool

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.contact.ContactEntity
import com.zhiban.rebuild.data.crm.CrmActionStatus
import com.zhiban.rebuild.data.crm.CrmLeadEntity
import com.zhiban.rebuild.data.crm.CrmLeadStatus
import com.zhiban.rebuild.data.crm.CrmNextActionEntity
import com.zhiban.rebuild.data.crm.CrmOpportunityEntity
import com.zhiban.rebuild.data.crm.CrmOpportunityStage
import com.zhiban.rebuild.data.crm.CrmRecordStatus
import com.zhiban.rebuild.runtime.governance.AutoWriteRepository
import com.zhiban.rebuild.runtime.kernel.PersistentRuntimeKernel
import com.zhiban.rebuild.runtime.kernel.RuntimeSignal
import com.zhiban.rebuild.runtime.store.RoomRuntimeStore
import com.zhiban.rebuild.runtime.store.RuntimeAttemptEntity
import com.zhiban.rebuild.runtime.store.RuntimeEventDraft
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomCrmToolExecutorTest {
    private lateinit var appContext: Context
    private lateinit var database: AgentDatabase
    private lateinit var store: RoomRuntimeStore
    private lateinit var executor: RoomCrmToolExecutor

    @Before
    fun setUp() = runBlocking {
        appContext = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(appContext, AgentDatabase::class.java).allowMainThreadQueries().build()
        store = RoomRuntimeStore(database, "crm-test")
        executor = RoomCrmToolExecutor(database, store)
        seedRealDomain()
    }

    @After fun tearDown() = database.close()

    @Test
    fun opportunityCreateWithoutPrimaryContactPersistsNullContact() = runBlocking {
        // #15: an opportunity may be created with no linked contact; the nullable FK must persist NULL.
        val sample = Sample(
            CrmMutationToolBinding.OPPORTUNITY_CREATE,
            """{"title":"无联系人机会","accountName":"乙公司","stage":"LEAD","currencyCode":"CNY","evidenceSummary":"客户确认需求"}""",
        )
        val plan = plan(91, sample)
        val context = fixture(91, plan, approved = true)

        val result = executor.execute(plan, context)

        assertTrue(result.safeResultJson.contains(CrmMutationToolBinding.OPPORTUNITY_CREATE))
        val created = database.crmDao().listOpportunityPageForExport(100, 0).single { it.title == "无联系人机会" }
        assertEquals(null, created.primaryContactId)
        assertEquals("乙公司", created.accountNameSnapshot)
    }

    @Test
    fun opportunityCreateConvertsSourceLeadAndRejectsASecondConversion() = runBlocking {
        database.crmDao().insertLead(
            CrmLeadEntity(
                "lead-create", "contact-1", "王建国", "甲公司", "NEW", "USER_CONFIRMED",
                null, null, 1.0, true, 1, 1,
            ),
        )
        val firstPlan = plan(
            92,
            Sample(
                CrmMutationToolBinding.OPPORTUNITY_CREATE,
                """{"title":"首次转化","accountName":"甲公司","sourceLeadId":"lead-create","stage":"LEAD","currencyCode":"CNY"}""",
            ),
        )
        val first = executor.execute(firstPlan, fixture(92, firstPlan, approved = true))

        assertEquals(CrmLeadStatus.CONVERTED, database.crmDao().findLead("lead-create")?.status)
        val createdOpportunityId = requireNotNull(
            database.crmDao().findOpportunityBySourceLead("lead-create"),
        ).opportunityId
        assertTrue(first.safeResultJson.contains(createdOpportunityId))

        val secondPlan = plan(
            93,
            Sample(
                CrmMutationToolBinding.OPPORTUNITY_CREATE,
                """{"title":"重复转化","accountName":"甲公司","sourceLeadId":"lead-create","stage":"LEAD","currencyCode":"CNY"}""",
            ),
        )
        val failure = runCatching { executor.execute(secondPlan, fixture(93, secondPlan, approved = true)) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals(createdOpportunityId, database.crmDao().findOpportunityBySourceLead("lead-create")?.opportunityId)
    }

    @Test
    fun allEightConfirmedToolsWriteAuditAndChangeRecords() = runBlocking {
        samples().forEachIndexed { index, sample ->
            val plan = plan(index, sample)
            val context = fixture(index, plan, approved = true)

            val result = executor.execute(plan, context)

            assertTrue(result.safeResultJson.contains(sample.name))
            assertNotNull(database.toolAuditDao().findByIdempotencyKey(plan.requiredText("idempotencyKey")))
            assertEquals(1, database.changeLogDao().listByRun("run-$index").size)
            assertEquals(1, database.runtimeToolExecutionDao().countByRunAndTool("run-$index", sample.name))
        }
        assertEquals(8, database.toolAuditDao().count())
        assertEquals(CrmActionStatus.COMPLETED, database.crmDao().findAction("action-1")?.status)
    }

    @Test
    fun allEightDuplicateSubmissionsReturnOriginalResultWithoutSecondWrite() = runBlocking {
        samples().forEachIndexed { index, sample ->
            val plan = plan(index, sample)
            val context = fixture(index, plan, approved = true)
            val first = executor.execute(plan, context)

            val replay = executor.execute(plan, context.copy(nowEpochMs = context.nowEpochMs + 1))

            assertEquals(first.safeResultJson, replay.safeResultJson)
            assertEquals(1, database.runtimeToolExecutionDao().countByRunAndTool("run-$index", sample.name))
            assertEquals(1, database.changeLogDao().listByRun("run-$index").size)
        }
        assertEquals(8, database.toolAuditDao().count())
    }

    @Test
    fun allEightWritesFailClosedWithoutPersistedApproval() = runBlocking {
        samples().forEachIndexed { index, sample ->
            val plan = plan(index, sample)
            val context = fixture(index, plan, approved = false)

            val failure = runCatching { executor.execute(plan, context) }.exceptionOrNull()

            assertTrue("${sample.name} must reject missing approval", failure is ToolPolicyRejectedException)
            assertEquals(0, database.runtimeToolExecutionDao().countByRunAndTool("run-$index", sample.name))
            assertEquals(0, database.changeLogDao().listByRun("run-$index").size)
        }
        assertEquals(0, database.toolAuditDao().count())
    }

    @Test
    fun ordinaryStageToolCannotReopenTerminalOpportunity() = runBlocking {
        val terminal = requireNotNull(database.crmDao().findOpportunity("opp-1")).copy(
            stage = CrmOpportunityStage.WON,
            status = CrmRecordStatus.WON,
            probabilityPercent = 100,
        )
        assertEquals(1, database.crmDao().updateOpportunity(terminal))
        val sample = Sample(
            CrmMutationToolBinding.OPPORTUNITY_STAGE,
            """{"opportunityId":"opp-1","stage":"LEAD","reason":"错误回退","evidenceSummary":"测试"}""",
        )
        val plan = plan(90, sample)
        val context = fixture(90, plan, approved = true)

        val failure = runCatching { executor.execute(plan, context) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals(CrmOpportunityStage.WON, database.crmDao().findOpportunity("opp-1")?.stage)
        assertEquals(CrmRecordStatus.WON, database.crmDao().findOpportunity("opp-1")?.status)
    }

    @Test
    fun automaticLeadEntersCandidatePoolAndReplayIsIdempotent() = runBlocking {
        val sample = Sample(
            CrmMutationToolBinding.LEAD_CREATE,
            """{"contactId":"contact-1","fitSummary":"连续询价","confidence":0.99,"evidenceSummary":"两次明确询价","sourceRef":"notification-42"}""",
        )
        val plan = plan(101, sample)
        val context = autoFixture(101)

        val first = executor.executeAuto(plan, context)
        val replay = executor.executeAuto(plan, context.copy(nowEpochMs = 31))

        assertEquals(first.safeResultJson, replay.safeResultJson)
        val lead = requireNotNull(database.crmDao().findLead(first.targetId))
        assertEquals(CrmLeadStatus.CANDIDATE, lead.status)
        assertEquals("AGENT_AUTO", lead.sourceType)
        assertEquals(false, lead.userConfirmed)
        assertEquals(0, database.crmDao().observeLeads().first().count { it.leadId == lead.leadId })
        assertEquals(lead.leadId, database.crmDao().observeCandidateLeads().first().single().leadId)
        assertEquals(1, database.changeLogDao().listByRun("run-101").size)
        assertNotNull(
            database.changeLogDao().findAutoWriteReceipt(
                database.changeLogDao().listByRun("run-101").single().changeId,
            ),
        )
    }

    @Test
    fun candidatePromotionEntersFormalListAndIgnoreRemovesCandidate() = runBlocking {
        val repository = AutoWriteRepository(database, appContext)
        val promoted = executor.executeAuto(
            plan(
                102,
                Sample(
                    CrmMutationToolBinding.LEAD_CREATE,
                    """{"contactId":"contact-1","fitSummary":"明确需求","confidence":0.99,"evidenceSummary":"客户明确需求","sourceRef":"message-promote"}""",
                ),
            ),
            autoFixture(102),
        )
        assertEquals(1, database.crmDao().countForecastEligibleLeads())
        assertTrue(repository.promoteCandidateLead(promoted.targetId, 50))
        assertEquals(CrmLeadStatus.QUALIFIED, database.crmDao().findLead(promoted.targetId)?.status)
        assertTrue(database.crmDao().observeLeads().first().any { it.leadId == promoted.targetId })
        assertTrue(database.crmDao().observeCandidateLeads().first().none { it.leadId == promoted.targetId })
        assertEquals(2, database.crmDao().countForecastEligibleLeads())

        val ignored = executor.executeAuto(
            plan(
                103,
                Sample(
                    CrmMutationToolBinding.LEAD_CREATE,
                    """{"contactId":"contact-1","fitSummary":"可能有意向","confidence":0.99,"evidenceSummary":"客户询问方案","sourceRef":"message-ignore"}""",
                ),
            ),
            autoFixture(103),
        )
        assertTrue(repository.ignoreCandidateLead(ignored.targetId, 60))
        assertEquals(null, database.crmDao().findLead(ignored.targetId))
        assertTrue(database.crmDao().observeCandidateLeads().first().none { it.leadId == ignored.targetId })
    }

    @Test
    fun undoDoesNotOverwriteCandidateChangedAfterAutomaticWrite() = runBlocking {
        val repository = AutoWriteRepository(database, appContext)
        val result = executor.executeAuto(
            plan(
                104,
                Sample(
                    CrmMutationToolBinding.LEAD_CREATE,
                    """{"contactId":"contact-1","fitSummary":"候选","confidence":0.99,"evidenceSummary":"明确询问","sourceRef":"message-change"}""",
                ),
            ),
            autoFixture(104),
        )
        database.openHelper.writableDatabase.execSQL(
            "UPDATE crm_leads SET fitSummary = '用户后来补充的判断', updatedAtEpochMs = 99 WHERE leadId = ?",
            arrayOf(result.targetId),
        )
        val change = database.changeLogDao().listByRun("run-104").single()

        assertEquals(false, repository.undo(change.changeId, 100))
        assertEquals("用户后来补充的判断", database.crmDao().findLead(result.targetId)?.fitSummary)
        assertEquals("AVAILABLE", database.changeLogDao().find(change.changeId)?.undoState)
    }

    @Test
    fun automaticActivityAndNextActionAreVisibleAndReversible() = runBlocking {
        val repository = AutoWriteRepository(database, appContext)
        val activity = executor.executeAuto(
            plan(
                105,
                Sample(
                    CrmMutationToolBinding.ACTIVITY_APPEND,
                    """{"opportunityId":"opp-1","contactId":"contact-1","activityType":"MEETING","title":"需求沟通","summary":"确认范围","occurredAtEpochMs":20,"evidenceSummary":"消息记录","sourceRef":"chat-105"}""",
                ),
            ),
            autoFixture(105),
        )
        val action = executor.executeAuto(
            plan(
                106,
                Sample(
                    CrmMutationToolBinding.ACTION_CREATE,
                    """{"opportunityId":"opp-1","contactId":"contact-1","actionType":"CALL","title":"确认预算","dueAtEpochMs":200000,"priority":80,"evidenceSummary":"客户要求下周联系"}""",
                ),
            ),
            autoFixture(106),
        )
        assertEquals("AGENT_AUTO", database.crmDao().findActivity(activity.targetId)?.sourceType)
        assertEquals("AGENT_AUTO", database.crmDao().findAction(action.targetId)?.sourceType)
        val activityChange = database.changeLogDao().listByRun("run-105").single()
        val actionChange = database.changeLogDao().listByRun("run-106").single()
        assertNotNull(database.changeLogDao().findAutoWriteReceipt(activityChange.changeId))
        assertNotNull(database.changeLogDao().findAutoWriteReceipt(actionChange.changeId))
        assertTrue(repository.undo(activityChange.changeId, 50))
        assertTrue(repository.undo(actionChange.changeId, 50))
        assertEquals(null, database.crmDao().findActivity(activity.targetId))
        assertEquals(null, database.crmDao().findAction(action.targetId))
    }

    private suspend fun autoFixture(index: Int): RuntimeToolRouteContext {
        val runId = "run-$index"
        val sessionId = "session-$index"
        val attemptId = "attempt-$index"
        store.acceptStart("start-$index", sessionId, runId, "{}", 1)
        val lease = store.claimSession(sessionId, "owner", 2, 1_000)
        val kernel = PersistentRuntimeKernel(store)
        kernel.transition(runId, RuntimeSignal.BeginContext, "owner", lease.leaseEpoch, 3)
        kernel.transition(runId, RuntimeSignal.ContextReady, "owner", lease.leaseEpoch, 4)
        database.runtimeAttemptDao().insert(RuntimeAttemptEntity(attemptId, runId, 1, "ACTIVE", 4, 4))
        database.openHelper.writableDatabase.execSQL(
            "UPDATE runtime_runs SET activeAttemptId = ? WHERE runId = ?",
            arrayOf(attemptId, runId),
        )
        return RuntimeToolRouteContext(runId, sessionId, attemptId, "owner", lease.leaseEpoch, 1, 30)
    }

    private suspend fun fixture(index: Int, plan: JsonObject, approved: Boolean): ConfirmedToolExecutionContext {
        val runId = "run-$index"
        val sessionId = "session-$index"
        val attemptId = "attempt-$index"
        store.acceptStart("start-$index", sessionId, runId, "{}", 1)
        val lease = store.claimSession(sessionId, "owner", 2, 1_000)
        val kernel = PersistentRuntimeKernel(store)
        kernel.transition(runId, RuntimeSignal.BeginContext, "owner", lease.leaseEpoch, 3)
        kernel.transition(runId, RuntimeSignal.ContextReady, "owner", lease.leaseEpoch, 4)
        kernel.transition(runId, RuntimeSignal.ModelReady, "owner", lease.leaseEpoch, 5)
        kernel.transition(runId, RuntimeSignal.PlanValidated, "owner", lease.leaseEpoch, 6)
        database.runtimeAttemptDao().insert(RuntimeAttemptEntity(attemptId, runId, 1, "ACTIVE", 6, 6))
        database.openHelper.writableDatabase.execSQL(
            "UPDATE runtime_runs SET activeAttemptId = ? WHERE runId = ?",
            arrayOf(attemptId, runId),
        )
        if (approved) {
            store.appendEvent(
                RuntimeEventDraft(
                    "approval-$index", "ApprovalRequested", sessionId, runId, attemptId,
                    plan.requiredText("providerCallId"), runId, plan.toString(), 7,
                ),
                "owner",
                lease.leaseEpoch,
                7,
            )
        }
        kernel.transition(runId, RuntimeSignal.Approved, "owner", lease.leaseEpoch, 8)
        return ConfirmedToolExecutionContext(runId, "owner", lease.leaseEpoch, 30)
    }

    private fun plan(index: Int, sample: Sample): JsonObject {
        val runId = "run-$index"
        val attemptId = "attempt-$index"
        val providerCallId = "call-$index"
        val rawData = Json.parseToJsonElement(sample.arguments).jsonObject
        val data = buildJsonObject {
            rawData.forEach { (key, value) ->
                put(if (key == "title" && sample.name in TOOLS_WITH_BUSINESS_TITLE) "crmTitle" else key, value)
            }
        }
        val digest = sha256(data.toString())
        return buildJsonObject {
            put("toolName", sample.name)
            put("providerCallId", providerCallId)
            put("logicalStepId", "step-$index")
            put("proposalId", "proposal-$index")
            put("payloadRef", "crm-plan-$digest")
            put("revision", 7)
            put("canonicalInputDigest", digest)
            put("idempotencyKey", sha256("$runId|$attemptId|$providerCallId|${sample.name}|1|$digest"))
            put("runId", runId)
            put("attemptId", attemptId)
            data.forEach { (key, value) -> put(key, value) }
            put("title", "测试确认")
            put("message", "完整字段确认")
        }.also { parseAndValidateCrmPlan(it.toString(), sample.name) }
    }

    private suspend fun seedRealDomain() {
        database.contactDao().insert(
            ContactEntity(
                "contact-1", "王建国", "王建国", null, null, null, "甲公司", "总监",
                "[]", "[]", null, null, "MANUAL", null, 1, 1,
            ),
        )
        database.crmDao().insertLead(
            CrmLeadEntity(
                "lead-1", "contact-1", "王建国", "甲公司", "NEW", "USER_CONFIRMED",
                null, null, 1.0, true, 1, 1,
            ),
        )
        database.crmDao().insertOpportunity(
            CrmOpportunityEntity(
                "opp-1", "私有化部署", "甲公司", "contact-1", "lead-1", "QUALIFIED", "OPEN",
                10_000, "CNY", 45, 100_000, null, null, null, "USER_CONFIRMED", 1, 1,
            ),
        )
        database.crmDao().insertAction(
            CrmNextActionEntity(
                "action-1", "opp-1", "contact-1", "CALL", "确认预算", 200_000, "PENDING",
                80, "需求已确认", "USER_CONFIRMED", null, 1, 1,
            ),
        )
    }

    private data class Sample(val name: String, val arguments: String)

    private fun samples() = listOf(
        Sample(
            CrmMutationToolBinding.LEAD_CREATE,
            """{"contactId":"contact-1","fitSummary":"需求明确","confidence":0.9,"evidenceSummary":"会议纪要"}""",
        ),
        Sample(
            CrmMutationToolBinding.OPPORTUNITY_CREATE,
            """{"title":"新机会","accountName":"甲公司","primaryContactId":"contact-1","stage":"LEAD","valueMinor":10000,"currencyCode":"CNY","expectedCloseAtEpochMs":300000,"evidenceSummary":"客户确认"}""",
        ),
        Sample(
            CrmMutationToolBinding.OPPORTUNITY_UPDATE,
            """{"opportunityId":"opp-1","valueMinor":20000,"primaryContactId":"contact-1","expectedCloseAtEpochMs":400000,"evidenceSummary":"更新预算"}""",
        ),
        Sample(
            CrmMutationToolBinding.OPPORTUNITY_STAGE,
            """{"opportunityId":"opp-1","stage":"PROPOSAL","reason":"需求已确认","evidenceSummary":"会议纪要"}""",
        ),
        Sample(
            CrmMutationToolBinding.ACTIVITY_APPEND,
            """{"opportunityId":"opp-1","contactId":"contact-1","activityType":"MEETING","title":"需求会","summary":"确认预算","occurredAtEpochMs":100000,"evidenceSummary":"会议纪要"}""",
        ),
        Sample(
            CrmMutationToolBinding.ACTION_CREATE,
            """{"opportunityId":"opp-1","contactId":"contact-1","actionType":"CALL","title":"确认决策人","dueAtEpochMs":200000,"priority":80,"evidenceSummary":"需求已确认"}""",
        ),
        Sample(
            CrmMutationToolBinding.ACTION_UPDATE,
            """{"actionId":"action-1","title":"确认预算和决策人","priority":90,"evidenceSummary":"客户补充"}""",
        ),
        Sample(
            CrmMutationToolBinding.ACTION_COMPLETE,
            """{"actionId":"action-1","completionNote":"已确认","calendarTitle":"发送方案","calendarStartAtEpochMs":300000,"calendarDurationMinutes":30,"calendarNote":"另行确认"}""",
        ),
    )

    private companion object {
        val TOOLS_WITH_BUSINESS_TITLE = setOf(
            CrmMutationToolBinding.OPPORTUNITY_CREATE,
            CrmMutationToolBinding.OPPORTUNITY_UPDATE,
            CrmMutationToolBinding.ACTIVITY_APPEND,
            CrmMutationToolBinding.ACTION_CREATE,
            CrmMutationToolBinding.ACTION_UPDATE,
        )
    }
}
