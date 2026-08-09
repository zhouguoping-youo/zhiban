package com.zhiban.rebuild.runtime.store

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.runtime.spi.CommandReceiptStatus
import com.zhiban.rebuild.runtime.spi.RuntimeAction
import com.zhiban.rebuild.runtime.spi.RuntimeUiCommand
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RuntimeGatewayTest {
    private lateinit var database: AgentDatabase
    private lateinit var gateways: RoomRuntimeGateways

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AgentDatabase::class.java,
        ).allowMainThreadQueries().build()
        gateways = RoomRuntimeGateways(database, "test") { now++ }
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun sixCommandsPersistWithCasAndDuplicateDoesNotAppendAgain() = runBlocking {
        val start = startCommand("start-1", 0)
        val accepted = gateways.accept(start)
        assertEquals(CommandReceiptStatus.ACCEPTED, accepted.status)
        assertEquals(1, accepted.currentRevision)
        assertEquals(CommandReceiptStatus.DUPLICATE, gateways.accept(start).status)

        val commands = listOf(
            action(RuntimeAction.APPROVE, "approve-1", 1, proposalId = "p1"),
            action(RuntimeAction.REJECT, "reject-1", 2, proposalId = "p1"),
            action(RuntimeAction.CANCEL, "cancel-1", 3),
            action(RuntimeAction.RETRY, "retry-1", 4),
            action(RuntimeAction.RESUME, "resume-1", 5),
        )
        commands.forEachIndexed { index, command ->
            val receipt = gateways.accept(command)
            assertEquals(CommandReceiptStatus.ACCEPTED, receipt.status)
            assertEquals((index + 2).toLong(), receipt.currentRevision)
        }
        assertEquals(6, database.runtimeEventDao().listAfter("s1", 0).size)
        assertEquals(6, database.runtimeCommandInboxDao().countBySession("s1"))
        assertTrue(database.runtimeCommandInboxDao().find("start-1")?.receiptJson?.startsWith("ACCEPTED|") == true)
    }

    @Test
    fun staleRevisionAndChangedDuplicateReturnConflictWithoutWrite() = runBlocking {
        gateways.accept(startCommand("start-1", 0))
        val stale = gateways.accept(action(RuntimeAction.APPROVE, "approve-stale", 0, proposalId = "p1"))
        assertEquals(CommandReceiptStatus.CONFLICT, stale.status)
        assertEquals(1, stale.currentRevision)

        val changed = gateways.accept(startCommand("start-1", 1, inputRef = "changed"))
        assertEquals(CommandReceiptStatus.CONFLICT, changed.status)
        assertEquals(1, database.runtimeEventDao().listAfter("s1", 0).size)
        assertEquals(1, database.runtimeCommandInboxDao().countBySession("s1"))
    }

    @Test
    fun snapshotThenObserveCatchesEventsWrittenBeforeCollectionWithoutDuplicates() = runBlocking {
        gateways.accept(startCommand("start-1", 0))
        val stream = gateways.snapshotAndObserve("s1", "ui", 0)
        gateways.accept(action(RuntimeAction.CANCEL, "cancel-1", 1))

        val batch = withTimeout(2_000) { stream.events.first { it.size == 2 } }
        assertEquals(listOf(1L, 2L), batch.map { it.sequence })
        assertEquals(1L, stream.snapshot.currentRevision)
        assertEquals(2, batch.map { it.sequence }.distinct().size)
    }

    @Test
    fun activeObserverEmitsMonotonicCatchUpAfterNewCommand() = runBlocking {
        gateways.accept(startCommand("start-1", 0))
        val stream = gateways.snapshotAndObserve("s1", "ui", 1)
        val next = async { withTimeout(2_000) { stream.events.first { it.isNotEmpty() } } }
        gateways.accept(action(RuntimeAction.CANCEL, "cancel-1", 1))
        assertEquals(listOf(2L), next.await().map { it.sequence })
    }

    @Test
    fun retryAttemptsRemainDistinctInStoredEventEnvelope() = runBlocking {
        gateways.accept(startCommand("start-1", 0))
        val store = RoomRuntimeStore(database, "test")
        val lease = store.claimSession("s1", "owner", now++, 1_000)
        database.runtimeAttemptDao().insert(RuntimeAttemptEntity("a1", "r1", 1, "FAILED", now, now))
        database.runtimeAttemptDao().insert(RuntimeAttemptEntity("a2", "r1", 2, "ACTIVE", now, now))
        store.appendEvent(
            RuntimeEventDraft("delta-a1", "AssistantDelta", "s1", "r1", "a1", "a1", "r1", "{\"ordinal\":1}", now++),
            "owner",
            lease.leaseEpoch,
            now,
        )
        store.appendEvent(
            RuntimeEventDraft("delta-a2", "AssistantDelta", "s1", "r1", "a2", "a2", "r1", "{\"ordinal\":1}", now++),
            "owner",
            lease.leaseEpoch,
            now,
        )

        val events = gateways.snapshotAndObserve("s1", "ui", 1).events.first { it.size == 2 }
        assertEquals(listOf("a1", "a2"), events.map { it.attemptId })
    }

    @Test
    fun projectionSnapshotCarriesPersistedVersionEnvelopeAndLegacyIsUnknown() = runBlocking {
        gateways.accept(startCommand("start-1", 0))
        val store = RoomRuntimeStore(database, "test")
        val lease = store.claimSession("s1", "owner", now++, 1_000)
        store.saveProjection("ui", "s1", 1, "{\"screen\":\"ready\"}", "owner", lease.leaseEpoch, now++)
        val known = gateways.snapshotAndObserve("s1", "ui", 1).snapshot
        assertEquals(1, known.snapshotSchemaVersion)
        assertEquals("test", known.snapshotProducerVersion)
        assertEquals("{\"screen\":\"ready\"}", known.snapshotJson)

        database.runtimeProjectionDao().insert(RuntimeProjectionEntity("legacy", "s1", 1, "unversioned", now++))
        val unknown = gateways.snapshotAndObserve("s1", "legacy", 1).snapshot
        assertEquals(0, unknown.snapshotSchemaVersion)
        assertEquals("unknown", unknown.snapshotProducerVersion)
    }

    @Test
    fun actionIntakeNeverWritesPolicyOrLifecycleDomainEvents() = runBlocking {
        gateways.accept(startCommand("start-1", 0))
        gateways.accept(action(RuntimeAction.APPROVE, "approve-1", 1, proposalId = "missing"))
        gateways.accept(action(RuntimeAction.RETRY, "retry-1", 2))

        val events = database.runtimeEventDao().listAfter("s1", 1)
        assertEquals(listOf("CommandEnqueued", "CommandEnqueued"), events.map { it.eventType })
        assertTrue(events.none { it.eventType in setOf("ApprovalGranted", "RetryRequested") })
        assertEquals("RECEIVED", database.runtimeRunDao().find("r1")?.status)
    }

    @Test
    fun emptySessionSnapshotCanObserveSubsequentStartWithoutLoss() = runBlocking {
        val stream = gateways.snapshotAndObserve("empty", "ui", 0)
        assertEquals(0, stream.snapshot.currentRevision)
        assertEquals(0, stream.snapshot.lastAppliedSequence)
        assertEquals(null, stream.snapshot.snapshotJson)
        val firstEvent = async { withTimeout(2_000) { stream.events.first { it.isNotEmpty() } } }

        val start = RuntimeUiCommand.Start("empty", "input", "start-empty", "action-empty", 0, "chat", "run-empty")
        assertEquals(CommandReceiptStatus.ACCEPTED, gateways.accept(start).status)
        assertEquals(listOf(1L), firstEvent.await().map { it.sequence })
    }

    private fun startCommand(commandId: String, revision: Long, inputRef: String = "input-1") =
        RuntimeUiCommand.Start("s1", inputRef, commandId, "action-$commandId", revision, "chat", "r1")

    private fun action(action: RuntimeAction, commandId: String, revision: Long, proposalId: String? = null) =
        RuntimeUiCommand.RunAction(action, "s1", "r1", commandId, "action-$commandId", revision, "chat", proposalId)

    companion object {
        private var now = 100L
    }
}
