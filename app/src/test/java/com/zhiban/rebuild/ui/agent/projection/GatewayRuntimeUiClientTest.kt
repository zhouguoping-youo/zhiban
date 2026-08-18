package com.zhiban.rebuild.ui.agent.projection

import com.zhiban.rebuild.runtime.spi.CommandReceipt
import com.zhiban.rebuild.runtime.spi.CommandReceiptStatus
import com.zhiban.rebuild.runtime.spi.RuntimeCommandGateway
import com.zhiban.rebuild.runtime.spi.RuntimeProjectionGateway
import com.zhiban.rebuild.runtime.spi.RuntimeProjectionStream
import com.zhiban.rebuild.runtime.spi.RuntimeUiCommand
import com.zhiban.rebuild.runtime.spi.RuntimeUiEvent
import com.zhiban.rebuild.runtime.spi.StoredProjectionSnapshot
import com.zhiban.rebuild.runtime.spi.StoredRuntimeEvent
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayRuntimeUiClientTest {
    @Test
    fun `dispatch delegates only to command gateway`() = runTest {
        val commandGateway = FakeCommandGateway()
        val client = GatewayRuntimeUiClient(commandGateway, FakeProjectionGateway(emptySnapshot()))
        val command = RuntimeUiCommand.Start("s1", "input", "c1", "a1", 0, "chat")

        val receipt = client.dispatch(command)

        assertEquals(command, commandGateway.accepted.single())
        assertEquals(CommandReceiptStatus.ACCEPTED, receipt.status)
    }

    @Test
    fun `snapshot folds stored catch up events without room access`() = runTest {
        val events = listOf(stored("RunReceived", 1), stored("CancelRequested", 2))
        val client = GatewayRuntimeUiClient(
            FakeCommandGateway(),
            FakeProjectionGateway(emptySnapshot(currentRevision = 2), events),
        )

        val result = client.getSessionProjection("s1")

        assertEquals(2L, result.lastAppliedSequence)
        assertEquals(com.zhiban.rebuild.runtime.spi.RuntimeRunStatus.CANCEL_REQUESTED, result.runStatus)
    }

    @Test
    fun `legacy projection envelope becomes unsupported read only`() = runTest {
        val snapshot = emptySnapshot().copy(
            snapshotSchemaVersion = 0,
            snapshotProducerVersion = "unknown",
            snapshotJson = "legacy",
        )
        val client = GatewayRuntimeUiClient(FakeCommandGateway(), FakeProjectionGateway(snapshot))

        val result = client.getSessionProjection("s1")

        assertTrue(result.readOnly)
        assertTrue(result.allowedActions.isEmpty())
    }

    @Test
    fun `stored deltas preserve attempt identity across retry`() = runTest {
        val events = listOf(
            stored("AssistantDelta", 1, "a1", "{\"ordinal\":1,\"part\":\"旧\",\"final\":false}"),
            stored("AssistantDelta", 2, "a2", "{\"ordinal\":1,\"part\":\"新\",\"final\":true}"),
        )
        val client = GatewayRuntimeUiClient(FakeCommandGateway(), FakeProjectionGateway(emptySnapshot(), events))

        val mapped = client.observeSession("s1", 0).toList().filterIsInstance<RuntimeUiEvent.AssistantDelta>()

        assertEquals(listOf("a1", "a2"), mapped.map { it.attemptId })
        assertEquals(listOf(1L, 1L), mapped.map { it.ordinal })
    }

    @Test
    fun `malformed event payload emits a fixed degradation reason`() = runTest {
        val events = listOf(stored("AssistantDelta", 1, "a1", "not-json"))
        val client = GatewayRuntimeUiClient(
            FakeCommandGateway(),
            FakeProjectionGateway(emptySnapshot(), events),
        )

        val event = client.observeSession("s1", 0).toList().single() as RuntimeUiEvent.ProjectionDegraded

        assertEquals("projection_event_payload_invalid", event.reasonCode)
    }

    @Test
    fun `malformed snapshot becomes read only with a fixed degradation reason`() = runTest {
        val snapshot = emptySnapshot().copy(
            snapshotSchemaVersion = 1,
            snapshotJson = "not-json",
        )
        val client = GatewayRuntimeUiClient(FakeCommandGateway(), FakeProjectionGateway(snapshot))

        val projection = client.getSessionProjection("s1")

        assertTrue(projection.readOnly)
        assertEquals(setOf("projection_snapshot_payload_invalid"), projection.degradationReasons)
    }

    @Test
    fun `verified tool change is rehydrated as undoable after restart`() = runTest {
        val events = listOf(
            stored("RunReceived", 1),
            stored(
                "ToolSucceeded",
                2,
                payload = """{"scheduleId":"schedule-1","changeId":"change-1","undoAvailable":true}""",
            ),
            stored("RunCompleted", 3),
        )
        val snapshot = StoredProjectionSnapshot(
            sessionId = "s1",
            projectionName = "agent-ui",
            lastAppliedSequence = 3,
            currentRevision = 3,
            snapshotSchemaVersion = 1,
            snapshotProducerVersion = "test",
            snapshotJson = """{"runId":"r1","runStatus":"SUCCEEDED","assistantText":"已创建"}""",
        )
        val client = GatewayRuntimeUiClient(FakeCommandGateway(), FakeProjectionGateway(snapshot, events))

        val result = client.getSessionProjection("s1")

        assertEquals("change-1", result.lastChangeId)
        assertTrue(result.undoAvailable)
        assertTrue(com.zhiban.rebuild.runtime.spi.RuntimeAction.UNDO in result.allowedActions)
    }

    @Test
    fun `reconnect backfills assistant body from journal when snapshot skipped the deltas`() = runTest {
        // Watermark (3) is past the deltas (1,2): replay skips them, leaving an empty bubble. The body
        // must be restored from the durable conversation turn, not lost.
        val events = listOf(
            stored("AssistantDelta", 1, "a1", "{\"ordinal\":1,\"part\":\"第一\",\"final\":false}"),
            stored("AssistantDelta", 2, "a1", "{\"ordinal\":2,\"part\":\"第二\",\"final\":true}"),
            stored("RejectApplied", 3),
        )
        val snapshot = emptySnapshot(currentRevision = 3).copy(
            snapshotSchemaVersion = 1,
            snapshotProducerVersion = "test",
            snapshotJson = """{"runId":"r1","runStatus":"CANCELLED"}""",
            lastAppliedSequence = 3,
        )
        val client = GatewayRuntimeUiClient(
            FakeCommandGateway(),
            FakeProjectionGateway(snapshot, events, assistantTurn = "第一第二"),
        )

        val result = client.getSessionProjection("s1")

        assertEquals("第一第二", result.assistantText)
    }

    @Test
    fun `replayed deltas are not overwritten by the journal backfill`() = runTest {
        // Watermark 0: the deltas replay normally and already build the body, so the journal copy must
        // not clobber it.
        val events = listOf(
            stored("AssistantDelta", 1, "a1", "{\"ordinal\":1,\"part\":\"流式正文\",\"final\":true}"),
        )
        val client = GatewayRuntimeUiClient(
            FakeCommandGateway(),
            FakeProjectionGateway(emptySnapshot(), events, assistantTurn = "旧的历史正文"),
        )

        val result = client.getSessionProjection("s1")

        assertEquals("流式正文", result.assistantText)
    }

    private fun stored(type: String, sequence: Long, attemptId: String? = null, payload: String = "{}") =
        StoredRuntimeEvent("e$sequence", type, "s1", "r1", attemptId, sequence, 1, "test", payload)

    private fun emptySnapshot(currentRevision: Long = 0) = StoredProjectionSnapshot(
        sessionId = "s1",
        projectionName = "agent-ui",
        lastAppliedSequence = 0,
        currentRevision = currentRevision,
        snapshotSchemaVersion = 0,
        snapshotProducerVersion = "unknown",
        snapshotJson = null,
    )
}

private class FakeCommandGateway : RuntimeCommandGateway {
    val accepted = mutableListOf<RuntimeUiCommand>()
    override suspend fun accept(command: RuntimeUiCommand): CommandReceipt {
        accepted += command
        return CommandReceipt(CommandReceiptStatus.ACCEPTED, command.commandId, command.expectedRevision + 1)
    }
}

private class FakeProjectionGateway(
    private val snapshot: StoredProjectionSnapshot,
    private val events: List<StoredRuntimeEvent> = emptyList(),
    private val assistantTurn: String? = null,
) : RuntimeProjectionGateway {
    override suspend fun snapshotAndObserve(sessionId: String, projectionName: String, afterSequenceExclusive: Long) =
        RuntimeProjectionStream(snapshot, flowOf(events.filter { it.sequence > afterSequenceExclusive }))

    override suspend fun assistantTurnText(sessionId: String, runId: String): String? = assistantTurn
}
