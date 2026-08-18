package com.zhiban.rebuild.runtime.tool

import com.zhiban.rebuild.data.store.RuntimeAttemptEntity

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.agent.ScheduleEntity
import com.zhiban.rebuild.data.crm.CrmActionStatus
import com.zhiban.rebuild.data.crm.CrmNextActionEntity
import com.zhiban.rebuild.data.crm.CrmOpportunityEntity
import com.zhiban.rebuild.data.crm.CrmOpportunityStage
import com.zhiban.rebuild.data.crm.CrmRecordStatus
import com.zhiban.rebuild.data.facts.FactIndex
import com.zhiban.rebuild.runtime.kernel.PersistentRuntimeKernel
import com.zhiban.rebuild.runtime.kernel.RuntimeSignal
import com.zhiban.rebuild.runtime.store.RoomRuntimeStore
import com.zhiban.rebuild.runtime.store.RuntimeEventDraft
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomScheduleToolExecutorTest {

    private lateinit var database: AgentDatabase
    private lateinit var store: RoomRuntimeStore
    private lateinit var executor: RoomScheduleToolExecutor

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AgentDatabase::class.java).allowMainThreadQueries().build()
        store = RoomRuntimeStore(database, "test")
        executor = RoomScheduleToolExecutor(database)
    }

    @After fun tearDown() = database.close()

    @Test fun unconfirmedOrMismatchedApprovalWritesNothing() = runBlocking {
        val fixture = fixture()
        val call = call()
        assertTrue(runCatching { executor.execute(fixture, call, confirmation("wrong")) }.isFailure)
        assertEquals(0, database.scheduleDao().count())
        assertEquals(0, database.toolAuditDao().count())
        assertEquals(null, store.toolResult(call.idempotencyKey))
    }

    @Test fun confirmedWriteIsAtomicAndDuplicateReturnsOriginalResult() = runBlocking {
        var scheduled: ScheduleEntity? = null
        executor = RoomScheduleToolExecutor(database, onScheduleSaved = { scheduled = it })
        val fixture = fixture()
        val call = call()
        val first = executor.execute(fixture, call, confirmation())
        val replay = executor.execute(fixture.copy(nowEpochMs = 31), call, confirmation())
        assertEquals(first, replay)
        assertEquals(1, database.scheduleDao().count())
        assertEquals(1, database.toolAuditDao().count())
        assertEquals(first.safeResultJson, store.toolResult(call.idempotencyKey)?.safeResultJson)
        assertEquals("OBSERVING", store.runById("run")?.status)
        assertEquals("SUCCEEDED", store.recoverySnapshot("run", "ui").attempts.single().status)
        val schedule = database.scheduleDao().findById(call.scheduleId)!!
        assertEquals("run", schedule.createdByRuntimeRunId)
        assertEquals("attempt", schedule.createdByRuntimeAttemptId)
        assertEquals(10, schedule.reminderMinutesBefore)
        assertEquals(schedule, scheduled)
        assertEquals(
            listOf("schedule:${call.scheduleId}"),
            FactIndex(database).search(call.title, 31, 10).map {
                it.factId
            },
        )
        val audit = database.toolAuditDao().findByIdempotencyKey(call.idempotencyKey)!!
        assertEquals("run", audit.runtimeRunId)
        assertEquals("attempt", audit.runtimeAttemptId)
        assertTrue(store.recoverySnapshot("run", "ui").events.takeLast(2).all { it.attemptId == "attempt" })
        assertEquals(
            listOf("ToolExecutionStarted", "ToolSucceeded"),
            store.recoverySnapshot("run", "ui").events.takeLast(2).map {
                it.eventType
            },
        )
    }

    @Test fun confirmedScheduleLinksItsPendingCrmActionInTheSameTransaction() = runBlocking {
        database.crmDao().insertOpportunity(
            CrmOpportunityEntity(
                "opp-1", "武汉项目", "客户公司", null, null, CrmOpportunityStage.QUALIFIED,
                CrmRecordStatus.OPEN, null, "CNY", 45, null, null, null, null,
                "USER_CONFIRMED", 1, 1,
            ),
        )
        database.crmDao().insertAction(
            CrmNextActionEntity(
                "action-1", "opp-1", null, "FOLLOW_UP", "发送方案", 10_000,
                CrmActionStatus.PENDING, 80, null, "USER_CONFIRMED", null, 1, 1,
            ),
        )
        val unsigned = call().copy(
            crmActionId = "action-1",
            canonicalInputDigest = "0".repeat(64),
            idempotencyKey = "pending",
        )
        val withDigest = unsigned.copy(canonicalInputDigest = canonicalScheduleDigest(unsigned))
        val call = withDigest.copy(idempotencyKey = canonicalToolIdempotencyKey("run", "attempt", withDigest))
        val fixture = fixture(approvedCall = call)

        executor.execute(fixture, call, confirmationFor(call))

        val linkedAction = requireNotNull(database.crmDao().findAction("action-1"))
        assertEquals(call.scheduleId, linkedAction.scheduleId)
        assertEquals(call.startAtEpochMs, linkedAction.dueAtEpochMs)
    }

    @Test fun missingCrmActionRollsBackTheConfirmedScheduleAndItsAudit() = runBlocking {
        val unsigned = call().copy(
            crmActionId = "missing-action",
            canonicalInputDigest = "0".repeat(64),
            idempotencyKey = "pending",
        )
        val withDigest = unsigned.copy(canonicalInputDigest = canonicalScheduleDigest(unsigned))
        val call = withDigest.copy(idempotencyKey = canonicalToolIdempotencyKey("run", "attempt", withDigest))
        val fixture = fixture(approvedCall = call)

        val failure = runCatching { executor.execute(fixture, call, confirmationFor(call)) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals(0, database.scheduleDao().count())
        assertEquals(0, database.toolAuditDao().count())
        assertEquals(null, store.toolResult(call.idempotencyKey))
    }

    @Test fun idempotencyKeyWithDifferentCanonicalDigestIsConflictWithoutSecondWrite() = runBlocking {
        val fixture = fixture()
        val call = call()
        executor.execute(fixture, call, confirmation())
        val conflict = call.copy(canonicalInputDigest = "b".repeat(64))
        assertTrue(runCatching { executor.execute(fixture, conflict, confirmation()) }.isFailure)
        assertEquals(1, database.scheduleDao().count())
        assertEquals(1, database.toolAuditDao().count())
    }

    @Test fun resultSurvivesFileDatabaseReopenWithoutDuplicateBusinessWrite() = runBlocking<Unit> {
        database.close()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "runtime-tool-reopen.db"
        context.deleteDatabase(name)
        database = Room.databaseBuilder(context, AgentDatabase::class.java, name).allowMainThreadQueries().build()
        store = RoomRuntimeStore(database, "test")
        executor = RoomScheduleToolExecutor(database)
        val fixture = fixture()
        val first = executor.execute(fixture, call(), confirmation())
        database.close()

        database = Room.databaseBuilder(context, AgentDatabase::class.java, name).allowMainThreadQueries().build()
        store = RoomRuntimeStore(database, "test")
        executor = RoomScheduleToolExecutor(database)
        val recovered = executor.execute(fixture.copy(nowEpochMs = 40), call(), confirmation())
        assertEquals(first, recovered)
        assertEquals(1, database.scheduleDao().count())
        assertEquals(1, database.toolAuditDao().count())
        assertEquals(1, database.runtimeToolExecutionDao().findByKey(call().idempotencyKey)?.let { 1 })
        context.deleteDatabase(name)
    }

    @Test fun auditFailureRollsBackScheduleRuntimeResultAndRunEvents() = runBlocking {
        val fixture = fixture()
        val call = call()
        database.toolAuditDao().insert(
            com.zhiban.rebuild.data.agent.ToolAuditEntity(
                id = "audit-${sha256(call.idempotencyKey).take(32)}", runId = null, subjectRunDigest = "existing",
                toolCallId = "existing", toolName = "calendar.schedule.create", idempotencyKey = "other-key",
                argumentsDigest = "abcdef", status = "SUCCEEDED", resultJson = null, expiresAtEpochMs = null,
                createdAtEpochMs = 1, updatedAtEpochMs = 1,
            ),
        )
        val beforeEvents = store.recoverySnapshot("run", "ui").events.size
        assertTrue(runCatching { executor.execute(fixture, call, confirmation()) }.isFailure)
        assertEquals(0, database.scheduleDao().count())
        assertEquals(null, store.toolResult(call.idempotencyKey))
        assertEquals("EXECUTING", store.runById("run")?.status)
        assertEquals(beforeEvents, store.recoverySnapshot("run", "ui").events.size)
    }

    @Test fun eachScheduleFieldTamperIsRejectedWithoutAnyWrite() = runBlocking {
        val mutations = listOf<(ScheduleCreateToolCall) -> ScheduleCreateToolCall>(
            { it.copy(title = "篡改") },
            { it.copy(startAtEpochMs = it.startAtEpochMs + 1) },
            { it.copy(durationMinutes = it.durationMinutes + 1) },
            { it.copy(note = "篡改") },
            { it.copy(scheduleId = "tampered-${it.scheduleId}") },
            { it.copy(crmActionId = "tampered-crm-action") },
        )
        mutations.forEachIndexed { index, mutate ->
            val fixture = fixtureFor("run-$index", "session-$index", "attempt-$index")
            val original = callFor("run-$index", "attempt-$index", "schedule-$index")
            assertTrue(runCatching { executor.execute(fixture, mutate(original), confirmationFor(original)) }.isFailure)
        }
        assertEquals(0, database.scheduleDao().count())
        assertEquals(0, database.toolAuditDao().count())
    }

    @Test fun duplicateIdentityMismatchIsRejected() = runBlocking {
        val fixture = fixture()
        val original = call()
        executor.execute(fixture, original, confirmation())
        val changed = original.copy(logicalStepId = "other-step")
        assertTrue(runCatching { executor.execute(fixture, changed, confirmation()) }.isFailure)
        assertEquals(1, database.scheduleDao().count())
        assertEquals(1, database.toolAuditDao().count())
    }

    @Test fun invalidPlanAndDisabledFlagFailClosed() = runBlocking {
        assertTrue(runCatching { SchedulePlanValidator.validate("{\"toolName\":\"contacts.write\"}") }.isFailure)
        val fixture = fixture()
        assertTrue(
            runCatching {
                RoomScheduleToolExecutor(database, enabled = { false }).execute(fixture, call(), confirmation())
            }.isFailure,
        )
        assertEquals(0, database.scheduleDao().count())
    }

    @Test fun overlappingScheduleIsRejectedBeforeAnyAgentSideEffect() = runBlocking {
        val fixture = fixture()
        database.scheduleDao().insert(
            com.zhiban.rebuild.data.agent.ScheduleEntity(
                "existing", "已有会议", 5_000, 60, null, null, null, null, 1, 1,
            ),
        )

        val failure = runCatching { executor.execute(fixture, call(), confirmation()) }.exceptionOrNull()

        assertTrue(failure is CalendarScheduleConflictException)
        assertEquals(1, database.scheduleDao().count())
        assertEquals(0, database.toolAuditDao().count())
        assertEquals(null, store.toolResult(call().idempotencyKey))
        assertEquals("EXECUTING", store.runById("run")?.status)
    }

    @Test fun overlappingDeviceCalendarEventIsRecheckedAfterApprovalBeforeAnyWrite() = runBlocking {
        val fixture = fixture()
        executor = RoomScheduleToolExecutor(
            database,
            externalConflicts = com.zhiban.rebuild.data.calendar.ExternalCalendarConflictSource { start, end, _, _ ->
                listOf(
                    com.zhiban.rebuild.data.calendar.ExternalCalendarConflict(
                        "device-event",
                        "手机日历已有会议",
                        start,
                        end,
                    ),
                )
            },
        )

        val failure = runCatching { executor.execute(fixture, call(), confirmation()) }.exceptionOrNull()

        assertTrue(failure is CalendarScheduleConflictException)
        assertEquals(0, database.scheduleDao().count())
        assertEquals(0, database.toolAuditDao().count())
        assertEquals(null, store.toolResult(call().idempotencyKey))
        assertEquals("EXECUTING", store.runById("run")?.status)
    }

    @Test fun confirmedScheduleFromThePastIsRejectedBeforeAnySideEffect() = runBlocking {
        val unsigned = call().copy(
            startAtEpochMs = 1_000L,
            canonicalInputDigest = "0".repeat(64),
            idempotencyKey = "pending",
        )
        val withDigest = unsigned.copy(canonicalInputDigest = canonicalScheduleDigest(unsigned))
        val past = withDigest.copy(idempotencyKey = canonicalToolIdempotencyKey("run", "attempt", withDigest))
        val fixture = fixture(nowEpochMs = 1_000_000L, leaseTtlMs = 2_000_000L, approvedCall = past)

        val failure = runCatching { executor.execute(fixture, past, confirmationFor(past)) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals(0, database.scheduleDao().count())
        assertEquals(0, database.toolAuditDao().count())
        assertEquals(null, store.toolResult(past.idempotencyKey))
    }

    private suspend fun fixture(
        nowEpochMs: Long = 30,
        leaseTtlMs: Long = 1_000,
        approvedCall: ScheduleCreateToolCall = call(),
    ): ConfirmedToolExecutionContext {
        store.acceptStart("start", "session", "run", "{}", 1)
        val lease = store.claimSession("session", "owner", 2, leaseTtlMs)
        val kernel = PersistentRuntimeKernel(store)
        kernel.transition("run", RuntimeSignal.BeginContext, "owner", lease.leaseEpoch, 3)
        kernel.transition("run", RuntimeSignal.ContextReady, "owner", lease.leaseEpoch, 4)
        kernel.transition("run", RuntimeSignal.ModelReady, "owner", lease.leaseEpoch, 5)
        kernel.transition("run", RuntimeSignal.PlanValidated, "owner", lease.leaseEpoch, 6)
        database.runtimeAttemptDao().insert(
            com.zhiban.rebuild.data.store.RuntimeAttemptEntity("attempt", "run", 1, "ACTIVE", 6, 6),
        )
        database.openHelper.writableDatabase.execSQL(
            "UPDATE runtime_runs SET activeAttemptId = 'attempt' WHERE runId = 'run'",
        )
        store.appendEvent(
            RuntimeEventDraft("approval", "ApprovalRequested", "session", "run", "attempt", "plan", "run", "{\"proposalId\":\"proposal\",\"payloadRef\":\"payload\",\"revision\":7,\"canonicalInputDigest\":\"${approvedCall.canonicalInputDigest}\"}", 7),
            "owner",
            lease.leaseEpoch,
            7,
        )
        kernel.transition("run", RuntimeSignal.Approved, "owner", lease.leaseEpoch, 8)
        return ConfirmedToolExecutionContext("run", "owner", lease.leaseEpoch, nowEpochMs)
    }

    private suspend fun fixtureFor(runId: String, sessionId: String, attemptId: String): ConfirmedToolExecutionContext {
        store.acceptStart("start-$runId", sessionId, runId, "{}", 1)
        val lease = store.claimSession(sessionId, "owner", 2, 1_000)
        val kernel = PersistentRuntimeKernel(store)
        kernel.transition(runId, RuntimeSignal.BeginContext, "owner", lease.leaseEpoch, 3)
        kernel.transition(runId, RuntimeSignal.ContextReady, "owner", lease.leaseEpoch, 4)
        kernel.transition(runId, RuntimeSignal.ModelReady, "owner", lease.leaseEpoch, 5)
        kernel.transition(runId, RuntimeSignal.PlanValidated, "owner", lease.leaseEpoch, 6)
        database.runtimeAttemptDao().insert(
            com.zhiban.rebuild.data.store.RuntimeAttemptEntity(attemptId, runId, 1, "ACTIVE", 6, 6),
        )
        database.openHelper.writableDatabase.execSQL(
            "UPDATE runtime_runs SET activeAttemptId = '$attemptId' WHERE runId = '$runId'",
        )
        val approved = callFor(runId, attemptId, "schedule-$runId")
        store.appendEvent(
            RuntimeEventDraft(
                "approval-$runId", "ApprovalRequested", sessionId, runId, attemptId, "plan", runId,
                "{\"proposalId\":\"proposal\",\"payloadRef\":\"payload\",\"revision\":7,\"canonicalInputDigest\":\"${approved.canonicalInputDigest}\"}", 7,
            ),
            "owner",
            lease.leaseEpoch,
            7,
        )
        kernel.transition(runId, RuntimeSignal.Approved, "owner", lease.leaseEpoch, 8)
        return ConfirmedToolExecutionContext(runId, "owner", lease.leaseEpoch, 30)
    }

    private fun callFor(runId: String, attemptId: String, scheduleId: String): ScheduleCreateToolCall {
        val unsigned = ScheduleCreateToolCall(
            "call-1", "step-1", "proposal", "payload", 7, "0".repeat(64), "pending",
            scheduleId, "复诊", 10_000, 30, null,
        )
        val withDigest = unsigned.copy(canonicalInputDigest = canonicalScheduleDigest(unsigned))
        return withDigest.copy(idempotencyKey = canonicalToolIdempotencyKey(runId, attemptId, withDigest))
    }
    private fun confirmationFor(call: ScheduleCreateToolCall) = ToolConfirmation(call.proposalId, call.payloadRef, call.revision, call.canonicalInputDigest)

    private fun call(): ScheduleCreateToolCall {
        val unsigned = ScheduleCreateToolCall(
            providerCallId = "call-1", logicalStepId = "step-1", proposalId = "proposal", payloadRef = "payload",
            revision = 7, canonicalInputDigest = "0".repeat(64), idempotencyKey = "pending",
            scheduleId = "schedule-1", title = "复诊", startAtEpochMs = 10_000, durationMinutes = 30, note = null,
            reminderMinutesBefore = 10,
        )
        val withDigest = unsigned.copy(canonicalInputDigest = canonicalScheduleDigest(unsigned))
        return withDigest.copy(idempotencyKey = canonicalToolIdempotencyKey("run", "attempt", withDigest))
    }
    private fun confirmation(proposalId: String = "proposal") = ToolConfirmation(proposalId, "payload", 7, call().canonicalInputDigest)
}
