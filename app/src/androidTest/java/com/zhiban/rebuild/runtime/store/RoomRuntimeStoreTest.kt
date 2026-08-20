package com.zhiban.rebuild.runtime.store

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.runtime.kernel.PersistentRuntimeKernel
import com.zhiban.rebuild.runtime.kernel.RuntimeSignal
import com.zhiban.rebuild.runtime.spi.RuntimeAction
import com.zhiban.rebuild.runtime.spi.RuntimeRunStatus
import com.zhiban.rebuild.runtime.spi.RuntimeUiCommand
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomRuntimeStoreTest {
    private lateinit var database: AgentDatabase
    private lateinit var store: RoomRuntimeStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AgentDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = RoomRuntimeStore(database, producerVersion = "test")
    }

    @After fun tearDown() = database.close()

    @Test
    fun duplicateCommandReturnsPersistedReceiptWithoutSecondEvent() = runBlocking {
        val first = store.acceptStart(
            commandId = "c1",
            sessionId = "s1",
            runId = "r1",
            payloadJson = "{\"text\":\"hi\"}",
            nowEpochMs = 10,
        )
        val duplicate = store.acceptStart(
            commandId = "c1",
            sessionId = "s1",
            runId = "r1",
            payloadJson = "{\"text\":\"hi\"}",
            nowEpochMs = 20,
        )

        assertTrue(first.inserted)
        assertFalse(duplicate.inserted)
        assertEquals(first.receipt, duplicate.receipt)
        assertEquals(1, store.eventsAfter("s1", 0).size)
        assertEquals("RunReceived", store.eventsAfter("s1", 0).single().eventType)
    }

    @Test
    fun appendsEventsWithDatabaseAllocatedMonotonicSequence() = runBlocking {
        store.acceptStart("c1", "s1", "r1", "{}", 10)
        val second = store.appendEvent(
            RuntimeEventDraft("e2", "RunStarted", "s1", "r1", null, "c1", "c1", "{}", 11),
            ownerId = null,
            fencingEpoch = 0,
            nowEpochMs = 11,
        )

        assertEquals(2, second.sequence)
        assertEquals(listOf(1L, 2L), store.eventsAfter("s1", 0).map { it.sequence })
    }

    @Test
    fun expiredLeaseCanBeReclaimedAndRejectsStaleWriter() = runBlocking {
        store.acceptStart("c1", "s1", "r1", "{}", 10)
        val first = store.claimSession("s1", "owner-a", nowEpochMs = 20, leaseDurationMs = 100)
        assertEquals(1, first.leaseEpoch)
        assertFalse(store.tryClaimSession("s1", "owner-b", nowEpochMs = 50, leaseDurationMs = 100).claimed)

        val second = store.claimSession("s1", "owner-b", nowEpochMs = 121, leaseDurationMs = 100)
        assertEquals(2, second.leaseEpoch)
        val staleRejected = runCatching {
            store.appendEvent(
                RuntimeEventDraft("stale", "RunStarted", "s1", "r1", null, "c1", "c1", "{}", 122),
                "owner-a",
                first.leaseEpoch,
                122,
            )
        }.exceptionOrNull()
        assertTrue(staleRejected is FencingRejectedException)
    }

    @Test
    fun expiredClaimedCommandCanBeReclaimedByNewLeaseOwner() = runBlocking {
        store.acceptStart("c1", "s1", "r1", "{}", 10)
        val first = store.claimSession("s1", "owner-a", 20, 100)
        assertTrue(store.claimCommand("c1", "owner-a", first.leaseEpoch, 21))
        val second = store.claimSession("s1", "owner-b", 121, 100)

        assertTrue(store.claimCommand("c1", "owner-b", second.leaseEpoch, 122))
    }

    @Test(expected = CommandConflictException::class)
    fun sameCommandIdWithDifferentPayloadIsConflict() = runBlocking {
        store.acceptStart("c1", "s1", "r1", "{\"text\":\"hi\"}", 10)
        store.acceptStart("c1", "s1", "r1", "{\"text\":\"changed\"}", 20)
        Unit
    }

    @Test
    fun sameOwnerRenewsWithoutInvalidatingInflightEpoch() = runBlocking<Unit> {
        store.acceptStart("c1", "s1", "r1", "{}", 10)
        val first = store.claimSession("s1", "owner-a", 20, 100)
        val renewed = store.claimSession("s1", "owner-a", 50, 100)
        assertEquals(first.leaseEpoch, renewed.leaseEpoch)
        store.appendEvent(
            RuntimeEventDraft("e-renew", "RunStarted", "s1", "r1", null, "c1", "c1", "{}", 60),
            "owner-a",
            first.leaseEpoch,
            60,
        )
    }

    @Test
    fun sensitiveApprovalUsesEncryptedStagingAndRejectRemovesIt() = runBlocking {
        store.acceptStart("approval-start", "approval-session", "approval-run", "{}", 10)
        val lease = store.claimSession("approval-session", "owner", 20, 1_000)
        store.startAttempt(
            AttemptStartRequest("approval-attempt", "approval-run", 1, "owner", lease.leaseEpoch, 21),
        )
        val fullPlan =
            """{"toolName":"communication.message.compose","providerCallId":"call-1","logicalStepId":"step-1","proposalId":"proposal-1","payloadRef":"payload-1","revision":1,"canonicalInputDigest":"digest-1","idempotencyKey":"key-1","platform":"SMS","recipient":"13800000000","message":"隐私正文","title":"给张三发短信"}"""

        assertTrue(
            store.requestCommunicationApproval(
                fullPlan,
                "call-1",
                "approval-session",
                "approval-run",
                "approval-attempt",
                "owner",
                lease.leaseEpoch,
                22,
            ),
        )

        val event = requireNotNull(database.runtimeEventDao().latestByType("approval-run", "ApprovalRequested"))
        assertFalse(event.payloadJson.contains("13800000000"))
        assertFalse(event.payloadJson.contains("隐私正文"))
        assertFalse(event.payloadJson.contains("张三"))
        val journal = Json.parseToJsonElement(event.payloadJson).jsonObject
        val stagedRef = requireNotNull(journal["stagedApprovalRef"]?.jsonPrimitive?.content)
        assertEquals(fullPlan, store.pendingToolPlan("approval-run", 23))
        assertEquals("13800000000", store.stagedApprovalContent(stagedRef, 23)?.recipient)
        assertEquals("隐私正文", store.stagedApprovalContent(stagedRef, 23)?.message)

        val revision = event.sequence
        val command = RuntimeUiCommand.RunAction(
            action = RuntimeAction.REJECT,
            sessionId = "approval-session",
            runId = "approval-run",
            commandId = "reject-command",
            clientActionId = "reject-action",
            expectedRevision = revision,
            surfaceId = "test",
            proposalId = "proposal-1",
            payloadRef = "payload-1",
        )
        store.acceptExternalCommand(command, 24)
        assertTrue(store.claimCommand("reject-command", "owner", lease.leaseEpoch, 25))
        assertTrue(store.processClaimedCommand("reject-command", "owner", lease.leaseEpoch, 26))

        assertNull(store.pendingToolPlan("approval-run", 27))
        assertNull(store.stagedApprovalContent(stagedRef, 27))
    }

    @Test
    fun completedCommandAndToolResultSurviveDatabaseReopen() = runBlocking<Unit> {
        database.close()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "runtime-store-reopen.db"
        context.deleteDatabase(name)
        database = Room.databaseBuilder(context, AgentDatabase::class.java, name).allowMainThreadQueries().build()
        store = RoomRuntimeStore(database, "test")
        store.acceptStart("c1", "s1", "r1", "{}", 10)
        val lease = store.claimSession("s1", "owner-a", 11, 100)
        store.completeCommand("c1", "{\"status\":\"ok\"}", "owner-a", lease.leaseEpoch, 20)
        store.recordToolSuccess(
            "x1", "r1", "step1", "calendar.create", 1, "digest", "key", "result-1",
            "{\"scheduleId\":\"s\"}", "owner-a", lease.leaseEpoch, 20,
        )
        database.close()
        database = Room.databaseBuilder(context, AgentDatabase::class.java, name).allowMainThreadQueries().build()
        store = RoomRuntimeStore(database, "test")

        assertEquals("{\"status\":\"ok\"}", store.commandResult("c1"))
        assertEquals("{\"scheduleId\":\"s\"}", store.toolResult("key")?.safeResultJson)
        context.deleteDatabase(name)
    }

    @Test
    fun providerTerminalEventStatusAttemptAndInputDeletionRollbackTogether() = runBlocking {
        val input = RoomTextInputGateway(database, { true }, { 10 }).stage("atomic")
        RoomRuntimeGateways(database, "test") { 11 }
            .accept(
                com.zhiban.rebuild.runtime.spi.RuntimeUiCommand.Start(
                    "s-atomic",
                    input.inputRef,
                    "c-atomic",
                    "a-atomic",
                    0,
                    "chat",
                    "r-atomic",
                ),
            )
        com.zhiban.rebuild.runtime.kernel.KernelCommandProcessor(database, "owner", { true }, { 12 }).processNext()
        val session = database.runtimeSessionDao().find("s-atomic")!!
        store.startAttempt(AttemptStartRequest("attempt-atomic", "r-atomic", 1, "owner", session.leaseEpoch, 13))
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER abort_provider_input_delete BEFORE DELETE ON runtime_run_inputs BEGIN SELECT RAISE(ABORT, 'crash-at-input-delete'); END",
        )

        assertTrue(
            runCatching {
                store.finishProviderRun(
                    "r-atomic", "SUCCEEDED", "RunCompleted", "{}", "SUCCEEDED",
                    "owner", session.leaseEpoch, 14, deleteInput = true,
                )
            }.isFailure,
        )
        assertEquals("INFERENCING", database.runtimeRunDao().find("r-atomic")?.status)
        assertEquals("ACTIVE", database.runtimeAttemptDao().listByRunId("r-atomic").single().status)
        assertTrue(database.runtimeEventDao().listByRunId("r-atomic").none { it.eventType == "RunCompleted" })
        assertEquals("atomic", database.runtimeRunInputDao().findByRunId("r-atomic")?.rawText)
    }

    @Test
    fun assistantTurnAndProviderTerminalStateRollbackTogether() = runBlocking {
        val input = RoomTextInputGateway(database, { true }, { 10 }).stage("atomic reply")
        RoomRuntimeGateways(database, "test") { 11 }.accept(
            com.zhiban.rebuild.runtime.spi.RuntimeUiCommand.Start(
                "s-reply-atomic",
                input.inputRef,
                "c-reply-atomic",
                "a-reply-atomic",
                0,
                "chat",
                "r-reply-atomic",
            ),
        )
        com.zhiban.rebuild.runtime.kernel.KernelCommandProcessor(database, "owner", { true }, { 12 }).processNext()
        val session = database.runtimeSessionDao().find("s-reply-atomic")!!
        store.startAttempt(AttemptStartRequest("attempt-reply", "r-reply-atomic", 1, "owner", session.leaseEpoch, 13))
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER abort_reply_terminal BEFORE UPDATE OF status ON runtime_runs " +
                "WHEN NEW.status='SUCCEEDED' BEGIN SELECT RAISE(ABORT, 'crash-before-terminal'); END",
        )

        assertTrue(
            runCatching {
                store.completeProviderRunWithAssistantTurn(
                    "r-reply-atomic",
                    "操作已完成。",
                    "owner",
                    session.leaseEpoch,
                    14,
                )
            }.isFailure,
        )

        assertEquals("INFERENCING", database.runtimeRunDao().find("r-reply-atomic")?.status)
        assertEquals(null, database.runtimeConversationTurnDao().assistantTurnContent("s-reply-atomic", "r-reply-atomic"))
        assertTrue(database.runtimeEventDao().listByRunId("r-reply-atomic").none { it.eventType == "RunCompleted" })
    }

    @Test
    fun lateProviderEventsAfterTerminalOrSupersedeAreRejectedWithoutWrite() = runBlocking {
        val input = RoomTextInputGateway(database, { true }, { 10 }).stage("late")
        RoomRuntimeGateways(database, "test") { 11 }
            .accept(
                com.zhiban.rebuild.runtime.spi.RuntimeUiCommand.Start(
                    "s-late",
                    input.inputRef,
                    "c-late",
                    "a-late",
                    0,
                    "chat",
                    "r-late",
                ),
            )
        com.zhiban.rebuild.runtime.kernel.KernelCommandProcessor(database, "owner", { true }, { 12 }).processNext()
        val session = database.runtimeSessionDao().find("s-late")!!
        store.startAttempt(AttemptStartRequest("attempt-late", "r-late", 1, "owner", session.leaseEpoch, 13))
        store.finishProviderRun(
            "r-late", "SUCCEEDED", "RunCompleted", "{}", "SUCCEEDED",
            "owner", session.leaseEpoch, 14, deleteInput = true,
        )
        val before = database.runtimeEventDao().listByRunId("r-late").size

        assertTrue(
            runCatching {
                store.appendProviderEventOnce(
                    RuntimeEventDraft("late-delta", "AssistantDelta", "s-late", "r-late", "attempt-late", "attempt-late", "r-late", "{\"part\":\"late\"}", 15),
                    "owner",
                    session.leaseEpoch,
                    15,
                )
            }.isFailure,
        )
        assertEquals(before, database.runtimeEventDao().listByRunId("r-late").size)
    }

    @Test
    fun observationReplayWithChangedPayloadIsRejectedWithoutSecondEvent() = runBlocking {
        store.acceptStart("c-observe", "s-observe", "r-observe", "{}", 10)
        val lease = store.claimSession("s-observe", "owner", 11, 1_000)
        store.startAttempt(AttemptStartRequest("provider-observe", "r-observe", 1, "owner", lease.leaseEpoch, 12))
        store.finishProviderRun(
            "r-observe", RuntimeRunStatus.OBSERVING.name, "ObservationRequired", "{}", "SUCCEEDED",
            "owner", lease.leaseEpoch, 13, deleteInput = false,
        )
        store.startObservationAttempt(AttemptStartRequest("observation-1", "r-observe", 2, "owner", lease.leaseEpoch, 14))
        val original = RuntimeEventDraft(
            "observation-result", "ToolObservation", "s-observe", "r-observe", "observation-1",
            "observation-1", "r-observe", "{\"result\":\"first\"}", 15,
        )
        store.appendObservationEventOnce(original, "owner", lease.leaseEpoch, 15)

        val replayFailure = runCatching {
            store.appendObservationEventOnce(
                original.copy(payloadJson = "{\"result\":\"changed\"}", createdAtEpochMs = 16),
                "owner",
                lease.leaseEpoch,
                16,
            )
        }.exceptionOrNull()

        assertEquals("OBSERVATION_REPLAY_CONFLICT", replayFailure?.message)
        assertEquals(1, database.runtimeEventDao().listByRunId("r-observe").count { it.eventId == original.eventId })
    }

    @Test
    fun attemptRunEventAndProjectionArePersistedForRecovery() = runBlocking<Unit> {
        store.acceptStart("c1", "s1", "r1", "{}", 10)
        val lease = store.claimSession("s1", "owner-a", 20, 100)
        store.startAttempt(AttemptStartRequest("a1", "r1", 1, "owner-a", lease.leaseEpoch, 21))
        store.saveProjection("ui", "s1", 2, "{\"status\":\"INFERENCING\"}", "owner-a", lease.leaseEpoch, 22)

        val snapshot = store.recoverySnapshot("r1", "ui")
        assertEquals("a1", snapshot.run.activeAttemptId)
        assertEquals(1, snapshot.attempts.size)
        assertEquals("{\"status\":\"INFERENCING\"}", snapshot.projection?.snapshotJson)
        assertEquals(listOf(1L, 2L), snapshot.events.map { it.sequence })
    }

    @Test
    fun recoveryScannerOnlyReturnsExpiredNonTerminalSessions() = runBlocking {
        store.acceptStart("c1", "s1", "r1", "{}", 10)
        store.claimSession("s1", "owner-a", 20, 100)
        assertEquals(emptyList<String>(), store.recoverableSessionIds(119))
        assertEquals(listOf("s1"), store.recoverableSessionIds(120))
    }

    @Test
    fun runAndAttemptTerminalTransitionAppendEventAtomically() = runBlocking {
        store.acceptStart("c1", "s1", "r1", "{}", 10)
        val lease = store.claimSession("s1", "owner-a", 20, 100)
        store.startAttempt(AttemptStartRequest("a1", "r1", 1, "owner-a", lease.leaseEpoch, 21))
        store.transitionRun(
            "r1",
            "INFERENCING",
            "FAILED_RETRYABLE",
            "ProviderAttemptFailed",
            "FAILED",
            "owner-a",
            lease.leaseEpoch,
            22,
        )
        store.transitionRun(
            "r1",
            "FAILED_RETRYABLE",
            "INFERENCING",
            "ProviderAttemptStarted",
            null,
            "owner-a",
            lease.leaseEpoch,
            23,
        )

        val snapshot = store.recoverySnapshot("r1", "ui")
        assertEquals("INFERENCING", snapshot.run.status)
        assertEquals("FAILED", snapshot.attempts.single().status)
        assertEquals(
            listOf("RunReceived", "ProviderAttemptStarted", "ProviderAttemptFailed", "ProviderAttemptStarted"),
            snapshot.events.map {
                it.eventType
            },
        )
    }

    @Test
    fun fencedLedgersRejectOldWriterAndProjectionCannotMoveBackward() = runBlocking {
        store.acceptStart("c1", "s1", "r1", "{}", 10)
        val first = store.claimSession("s1", "owner-a", 20, 100)
        store.saveProjection("ui", "s1", 2, "new", "owner-a", first.leaseEpoch, 30)
        assertFalse(store.saveProjection("ui", "s1", 1, "old", "owner-a", first.leaseEpoch, 31))
        val second = store.claimSession("s1", "owner-b", 121, 100)

        assertTrue(
            runCatching {
                store.completeCommand("c1", "{}", "owner-a", first.leaseEpoch, 122)
            }.exceptionOrNull() is FencingRejectedException,
        )
        assertTrue(
            runCatching {
                store.recordToolSuccess("x1", "r1", "step", "tool", 1, "d", "k", "ref", "{}", "owner-a", first.leaseEpoch, 122)
            }.exceptionOrNull() is FencingRejectedException,
        )
        assertTrue(store.saveProjection("ui", "s1", 3, "latest", "owner-b", second.leaseEpoch, 122))
        assertEquals("latest", store.recoverySnapshot("r1", "ui").projection?.snapshotJson)
    }

    @Test
    fun recoveryClaimIsAtomicAndSnapshotContainsOnlyRequestedRun() = runBlocking {
        store.acceptStart("c1", "s1", "r1", "{}", 10)
        store.acceptStart("c2", "s1", "r2", "{}", 11)
        store.claimSession("s1", "dead", 20, 10)

        val first = store.claimRecoverable("recovery-a", 30, 100, "ui")
        val second = store.claimRecoverable("recovery-b", 30, 100, "ui")

        assertEquals(2, first.size)
        assertEquals(0, second.size)
        assertEquals(setOf("r1", "r2"), first.map { it.snapshot.run.runId }.toSet())
        assertEquals(listOf("r1"), store.recoverySnapshot("r1", "ui").events.map { it.runId }.distinct())
    }

    @Test
    fun cancelTimeoutAndRetryAreTransactionalAndDoNotDuplicateEventsOrToolResult() = runBlocking {
        store.acceptStart("c1", "s1", "r1", "{}", 10)
        val lease = store.claimSession("s1", "owner", 20, 200)
        store.startAttempt(AttemptStartRequest("a1", "r1", 1, "owner", lease.leaseEpoch, 21))
        store.transitionRun(
            "r1",
            "INFERENCING",
            "CANCEL_REQUESTED",
            "RunCancelRequested",
            null,
            "owner",
            lease.leaseEpoch,
            22,
        )
        store.transitionRun(
            "r1",
            "CANCEL_REQUESTED",
            "CANCELLED",
            "RunCancelled",
            "CANCELLED",
            "owner",
            lease.leaseEpoch,
            23,
        )
        val duplicateCancel = runCatching {
            store.transitionRun(
                "r1",
                "CANCEL_REQUESTED",
                "CANCELLED",
                "RunCancelled",
                null,
                "owner",
                lease.leaseEpoch,
                24,
            )
        }
        assertTrue(duplicateCancel.isFailure)
        assertEquals(4, store.recoverySnapshot("r1", "ui").events.size)

        store.acceptStart("c2", "s1", "r2", "{}", 30)
        store.startAttempt(AttemptStartRequest("a2", "r2", 1, "owner", lease.leaseEpoch, 31))
        store.transitionRun(
            "r2",
            "INFERENCING",
            "FAILED_RETRYABLE",
            "RunTimedOut",
            "FAILED",
            "owner",
            lease.leaseEpoch,
            32,
        )
        store.transitionRun(
            "r2",
            "FAILED_RETRYABLE",
            "INFERENCING",
            "RunRetryStarted",
            null,
            "owner",
            lease.leaseEpoch,
            33,
        )
        val first = store.recordToolSuccess("x1", "r2", "step", "tool", 1, "digest", "key-r2", "ref", "{}", "owner", lease.leaseEpoch, 34)
        val replay = store.recordToolSuccess("x2", "r2", "step", "tool", 1, "digest", "key-r2", "other", "{\"changed\":true}", "owner", lease.leaseEpoch, 35)
        assertEquals(first.executionId, replay.executionId)
        assertEquals(4, store.recoverySnapshot("r2", "ui").events.size)
    }

    @Test
    fun failedCommandResultIsPersistedAndFenced() = runBlocking {
        store.acceptStart("c1", "s1", "r1", "{}", 10)
        val lease = store.claimSession("s1", "owner", 20, 100)
        store.failCommand("c1", "{\"code\":\"TIMEOUT\"}", "owner", lease.leaseEpoch, 21)
        assertEquals("{\"code\":\"TIMEOUT\"}", store.commandResult("c1"))
    }

    @Test
    fun persistentKernelRejectsIllegalTransitionWithoutEventOrStateChange() = runBlocking {
        store.acceptStart("c1", "s1", "r1", "{}", 10)
        val lease = store.claimSession("s1", "owner", 20, 100)
        val kernel = PersistentRuntimeKernel(store)

        val rejected = runCatching {
            kernel.transition("r1", RuntimeSignal.ObservationCompleted, "owner", lease.leaseEpoch, 21)
        }
        assertTrue(rejected.isFailure)
        val snapshot = store.recoverySnapshot("r1", "ui")
        assertEquals("RECEIVED", snapshot.run.status)
        assertEquals(listOf("RunReceived"), snapshot.events.map { it.eventType })
    }

    @Test
    fun fileReopenRecoveryHandleCarriesSnapshotAndCanContinueWithClaimedLease() = runBlocking<Unit> {
        database.close()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "runtime-recovery-handle.db"
        context.deleteDatabase(name)
        database = Room.databaseBuilder(context, AgentDatabase::class.java, name).allowMainThreadQueries().build()
        store = RoomRuntimeStore(database, "test")
        store.acceptStart("c1", "s1", "r1", "{}", 10)
        val deadLease = store.claimSession("s1", "dead", 20, 20)
        store.startAttempt(AttemptStartRequest("a1", "r1", 1, "dead", deadLease.leaseEpoch, 21))
        store.saveProjection("ui", "s1", 2, "snapshot", "dead", deadLease.leaseEpoch, 22)
        database.close()

        database = Room.databaseBuilder(context, AgentDatabase::class.java, name).allowMainThreadQueries().build()
        store = RoomRuntimeStore(database, "test")
        val handle = store.claimRecoverable("recovery", 40, 100, "ui").single()
        assertEquals("r1", handle.snapshot.run.runId)
        assertEquals("a1", handle.snapshot.attempts.single().attemptId)
        assertEquals("snapshot", handle.snapshot.projection?.snapshotJson)
        assertEquals(2, handle.snapshot.run.recoveryCursor)
        store.transitionRun(
            "r1",
            "INFERENCING",
            "FAILED_RETRYABLE",
            "RecoveredAndSuspended",
            "FAILED",
            handle.ownerId,
            handle.leaseEpoch,
            41,
        )
        assertEquals("FAILED_RETRYABLE", store.recoverySnapshot("r1", "ui").run.status)
        context.deleteDatabase(name)
    }
}
