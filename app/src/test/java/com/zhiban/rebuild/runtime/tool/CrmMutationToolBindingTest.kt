package com.zhiban.rebuild.runtime.tool

import com.zhiban.rebuild.data.contact.ContactDao
import com.zhiban.rebuild.data.contact.ContactEntity
import com.zhiban.rebuild.data.crm.CrmDao
import com.zhiban.rebuild.data.crm.CrmLeadEntity
import com.zhiban.rebuild.data.crm.CrmNextActionEntity
import com.zhiban.rebuild.data.crm.CrmOpportunityEntity
import com.zhiban.rebuild.runtime.provider.ProviderFailure
import com.zhiban.rebuild.runtime.store.RoomRuntimeStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CrmMutationToolBindingTest {
    private val store = mockk<RoomRuntimeStore>()
    private val crm = mockk<CrmDao>()
    private val contacts = mockk<ContactDao>()
    private val executor = mockk<RoomCrmToolExecutor>()
    private val capturedPlans = mutableListOf<String>()
    private val contact = ContactEntity(
        "contact-1", "王建国", "王建国", null, null, null, "甲公司", "总监",
        "[]", "[]", null, null, "MANUAL", null, 1, 1,
    )
    private val opportunity = CrmOpportunityEntity(
        "opp-1", "私有化部署", "甲公司", "contact-1", "lead-1", "QUALIFIED", "OPEN",
        10_000, "CNY", 45, 100_000, null, null, null, "USER_CONFIRMED", 1, 1,
    )
    private val action = CrmNextActionEntity(
        "action-1", "opp-1", "contact-1", "CALL", "确认预算", 200_000, "PENDING",
        80, "需求已确认", "USER_CONFIRMED", null, 1, 1,
    )

    init {
        coEvery { contacts.findById(any()) } returns contact
        coEvery { crm.findOpportunity(any()) } returns opportunity
        coEvery { crm.findOpportunityBySourceLead(any()) } returns null
        coEvery { crm.findLead(any()) } returns CrmLeadEntity(
            "lead-1", "contact-1", "王建国", "甲公司", "NEW", "USER_CONFIRMED",
            null, null, 1.0, true, 1, 1,
        )
        coEvery { crm.findAction(any()) } returns action
        coEvery {
            store.requestCrmMutationApproval(capture(capturedPlans), any(), any(), any(), any(), any(), any(), any())
        } returns true
    }

    @Test
    fun `production catalog exposes thirty three tools including memory upsert and web search`() {
        val catalog = RuntimeToolCatalog.production()
        assertEquals(33, catalog.names().size)
        assertTrue(catalog.names().containsAll(CrmMutationToolBinding.TOOL_NAMES))
        assertTrue(ContactTagToolBinding.TOOL_NAME in catalog.names())
        assertTrue(MemoryUpsertToolBinding.TOOL_NAME in catalog.names())
        assertTrue(WebSearchToolBinding.TOOL_NAME in catalog.names())
        assertTrue(WechatSendToolBinding.TOOL_NAME in catalog.names())
    }

    @Test
    fun `all eight write tools build a complete per-call confirmation plan`() = runTest {
        samples().forEachIndexed { index, (name, arguments) ->
            val binding = binding(name)
            assertTrue(binding.requestApproval(RuntimeToolCallRequest("call-$index", name, arguments), context()))
            val plan = Json.parseToJsonElement(capturedPlans.last()).jsonObject
            assertEquals(name, plan.getValue("toolName").jsonPrimitive.content)
            assertTrue(plan.getValue("message").jsonPrimitive.content.contains("关联对象"))
            assertTrue(plan.getValue("canonicalInputDigest").jsonPrimitive.content.length == 64)
            assertTrue(plan.getValue("idempotencyKey").jsonPrimitive.content.length == 64)
            val expectedRisk = if (name in CrmMutationToolBinding.AUTO_TOOL_NAMES) {
                RuntimeToolRisk.REVERSIBLE_AUTO_WRITE
            } else {
                RuntimeToolRisk.WRITE_CONFIRMATION_REQUIRED
            }
            assertEquals(expectedRisk, binding.spec.risk)
        }
        assertEquals(8, capturedPlans.size)
    }

    @Test
    fun `agent retry of the same provider call produces the same idempotency key for every tool`() = runTest {
        samples().forEachIndexed { index, (name, arguments) ->
            capturedPlans.clear()
            val binding = binding(name)
            val request = RuntimeToolCallRequest("retry-$index", name, arguments)
            binding.requestApproval(request, context())
            binding.requestApproval(request, context())
            val keys = capturedPlans.map {
                Json.parseToJsonElement(it).jsonObject.getValue("idempotencyKey").jsonPrimitive.content
            }
            assertEquals("duplicate key for $name", keys.first(), keys.last())
        }
    }

    @Test
    fun `all CRM writes are rejected from the unconfirmed automatic path`() = runTest {
        val bindings = samples().map { (name, _) -> binding(name) }
        val router = CapabilityRouter(bindings, proposalCount = { _, _ -> 0 })
        samples().forEachIndexed { index, (name, arguments) ->
            val failure = runCatching {
                router.executeReadOnly(RuntimeToolCallRequest("call-$index", name, arguments), context())
            }.exceptionOrNull()
            assertTrue("$name must require confirmation", failure is ToolPolicyRejectedException)
        }
    }

    @Test
    fun `only high confidence reversible CRM creates enter automatic disposition`() = runTest {
        val autoSamples = samples().filter { it.first in CrmMutationToolBinding.AUTO_TOOL_NAMES }
        val router = CapabilityRouter(
            autoSamples.map { binding(it.first) },
            proposalCount = { _, _ -> 0 },
            policy = CapabilityPolicy(
                autoUndoTools = CrmMutationToolBinding.AUTO_TOOL_NAMES,
                autoPresentationTools = CrmMutationToolBinding.AUTO_TOOL_NAMES,
            ),
        )
        autoSamples.forEachIndexed { index, (name, arguments) ->
            assertEquals(
                ToolDisposition.ReversibleAutoWrite,
                router.disposition(RuntimeToolCallRequest("auto-$index", name, arguments), context()),
            )
        }
    }

    @Test
    fun `lead below automatic confidence threshold falls back to confirmation`() = runTest {
        val binding = binding(CrmMutationToolBinding.LEAD_CREATE)
        val router = CapabilityRouter(
            listOf(binding),
            proposalCount = { _, _ -> 0 },
            policy = CapabilityPolicy(
                autoUndoTools = CrmMutationToolBinding.AUTO_TOOL_NAMES,
                autoPresentationTools = CrmMutationToolBinding.AUTO_TOOL_NAMES,
            ),
        )
        val result = router.disposition(
            RuntimeToolCallRequest(
                "lead-low",
                CrmMutationToolBinding.LEAD_CREATE,
                """{"contactId":"contact-1","fitSummary":"需求待核实","confidence":0.9,"evidenceSummary":"单条消息","sourceRef":"message-1"}""",
            ),
            context(),
        )
        assertTrue(result is ToolDisposition.ConfirmationRequired)
    }

    // #15: a user may create an opportunity with no contact. The binding must still build a confirmation
    // plan (instead of throwing INVALID_TOOL_ARGUMENTS and looping), and the card subject must fall back
    // to the account name when there is no contact display name.
    @Test
    fun `opportunity create without primary contact still builds confirmation plan`() = runTest {
        capturedPlans.clear()
        val binding = binding(CrmMutationToolBinding.OPPORTUNITY_CREATE)
        val args = """{"title":"新机会","accountName":"甲公司","evidenceSummary":"客户确认需求"}"""
        assertTrue(
            binding.requestApproval(
                RuntimeToolCallRequest("call-no-contact", CrmMutationToolBinding.OPPORTUNITY_CREATE, args),
                context(),
            ),
        )
        val plan = Json.parseToJsonElement(capturedPlans.last()).jsonObject
        assertEquals(CrmMutationToolBinding.OPPORTUNITY_CREATE, plan.getValue("toolName").jsonPrimitive.content)
        assertTrue(plan.getValue("message").jsonPrimitive.content.contains("甲公司"))
    }

    // A supplied contact is still validated (guards hallucinated ids); the empty path must not weaken that.
    @Test
    fun `opportunity create with unknown contact is still rejected`() = runTest {
        coEvery { contacts.findById("ghost") } returns null
        val binding = binding(CrmMutationToolBinding.OPPORTUNITY_CREATE)
        val args = """{"title":"新机会","accountName":"甲公司","primaryContactId":"ghost","evidenceSummary":"客户确认需求"}"""
        val failure = runCatching {
            binding.requestApproval(
                RuntimeToolCallRequest("call-ghost", CrmMutationToolBinding.OPPORTUNITY_CREATE, args),
                context(),
            )
        }.exceptionOrNull()
        assertTrue(failure is ProviderFailure)
    }

    @Test
    fun `activity append with unknown contact is rejected before approval`() = runTest {
        coEvery { contacts.findById("missing-contact") } returns null
        val failure = runCatching {
            binding(CrmMutationToolBinding.ACTIVITY_APPEND).requestApproval(
                RuntimeToolCallRequest(
                    "call-missing-contact",
                    CrmMutationToolBinding.ACTIVITY_APPEND,
                    """{"opportunityId":"opp-1","contactId":"missing-contact","activityType":"NOTE","title":"记录","summary":"摘要","occurredAtEpochMs":1,"evidenceSummary":"测试"}""",
                ),
                context(),
            )
        }.exceptionOrNull()

        assertTrue(failure is ProviderFailure)
    }

    @Test
    fun `next action create with unknown opportunity is rejected before approval`() = runTest {
        coEvery { crm.findOpportunity("missing-opportunity") } returns null
        val failure = runCatching {
            binding(CrmMutationToolBinding.ACTION_CREATE).requestApproval(
                RuntimeToolCallRequest(
                    "call-missing-opportunity",
                    CrmMutationToolBinding.ACTION_CREATE,
                    """{"opportunityId":"missing-opportunity","actionType":"CALL","title":"跟进","priority":50,"evidenceSummary":"测试"}""",
                ),
                context(),
            )
        }.exceptionOrNull()

        assertTrue(failure is ProviderFailure)
    }

    private fun binding(name: String) = CrmMutationToolBinding(
        RuntimeToolCatalog.production().requireRegistered(name),
        store,
        crm,
        contacts,
        executor,
    )

    private fun context() = RuntimeToolRouteContext("run", "session", "attempt", "owner", 1, 7, 10)

    private fun samples() = listOf(
        CrmMutationToolBinding.LEAD_CREATE to
            """{"contactId":"contact-1","fitSummary":"需求明确","confidence":0.99,"evidenceSummary":"已授权会议纪要","sourceRef":"meeting-1"}""",
        CrmMutationToolBinding.OPPORTUNITY_CREATE to
            """{"title":"新机会","accountName":"甲公司","primaryContactId":"contact-1","sourceLeadId":"lead-1","valueMinor":10000,"expectedCloseAtEpochMs":300000,"evidenceSummary":"客户确认需求"}""",
        CrmMutationToolBinding.OPPORTUNITY_UPDATE to
            """{"opportunityId":"opp-1","valueMinor":20000,"primaryContactId":"contact-1","expectedCloseAtEpochMs":400000,"evidenceSummary":"客户更新预算"}""",
        CrmMutationToolBinding.OPPORTUNITY_STAGE to
            """{"opportunityId":"opp-1","stage":"PROPOSAL","reason":"需求已确认","evidenceSummary":"会议纪要"}""",
        CrmMutationToolBinding.ACTIVITY_APPEND to
            """{"opportunityId":"opp-1","contactId":"contact-1","activityType":"MEETING","title":"需求会","summary":"确认预算","occurredAtEpochMs":100000,"evidenceSummary":"已授权会议纪要","sourceRef":"meeting-1"}""",
        CrmMutationToolBinding.ACTION_CREATE to
            """{"opportunityId":"opp-1","contactId":"contact-1","actionType":"CALL","title":"确认预算","dueAtEpochMs":200000,"priority":80,"evidenceSummary":"需求已确认"}""",
        CrmMutationToolBinding.ACTION_UPDATE to
            """{"actionId":"action-1","title":"确认预算和决策人","priority":90,"evidenceSummary":"客户补充信息"}""",
        CrmMutationToolBinding.ACTION_COMPLETE to
            """{"actionId":"action-1","completionNote":"已电话确认","calendarTitle":"发送方案","calendarStartAtEpochMs":300000,"calendarDurationMinutes":30,"calendarNote":"需要另行确认"}""",
    )
}
