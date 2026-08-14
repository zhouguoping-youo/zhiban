package com.zhiban.rebuild.ui.agent.projection

import com.zhiban.rebuild.runtime.spi.CommandReceipt
import com.zhiban.rebuild.runtime.spi.CommandReceiptStatus
import com.zhiban.rebuild.runtime.spi.PendingApprovalProjection
import com.zhiban.rebuild.runtime.spi.RuntimeAction
import com.zhiban.rebuild.runtime.spi.RuntimeRunStatus
import com.zhiban.rebuild.runtime.spi.RuntimeUiClient
import com.zhiban.rebuild.runtime.spi.RuntimeUiCommand
import com.zhiban.rebuild.runtime.spi.RuntimeUiEvent
import com.zhiban.rebuild.runtime.spi.SessionProjection
import com.zhiban.rebuild.runtime.spi.StagedApprovalContent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AgentRuntimeProjectionControllerTest {
    @Test
    fun `projection snapshot failure becomes retryable and reconnects`() = runTest {
        val client = FakeRuntimeUiClient(SessionProjection(sessionId = "session-1")).apply {
            remainingSnapshotFailures = 1
        }
        val controller = AgentRuntimeProjectionController(
            client = client,
            sessionId = "session-1",
            surfaceId = "compose",
            scope = this,
            idFactory = { "id" },
            reconnectDelayMs = 10,
        )

        controller.initialize()
        runCurrent()
        assertEquals(RuntimeRunStatus.FAILED_RETRYABLE, controller.projection.value.runStatus)
        assertEquals("PROJECTION_UNAVAILABLE", controller.projection.value.safeFailureCode)

        advanceTimeBy(11)
        runCurrent()
        assertEquals(null, controller.projection.value.safeFailureCode)
        assertEquals(2, client.snapshotReads)
        controller.close()
    }

    @Test
    fun `initialize resumes after projection watermark and reduces new events`() = runTest {
        val client = FakeRuntimeUiClient(
            SessionProjection(sessionId = "session-1", lastAppliedSequence = 7, revision = 3),
        )
        val controller = AgentRuntimeProjectionController(
            client = client,
            sessionId = "session-1",
            surfaceId = "compose",
            scope = this,
            idFactory = { "id" },
        )

        controller.initialize()
        runCurrent()
        client.events.emit(
            RuntimeUiEvent.RunStatusChanged(
                sessionId = "session-1",
                runId = "run-1",
                sequence = 8,
                revision = 4,
                status = RuntimeRunStatus.EXECUTING,
            ),
        )
        runCurrent()

        controller.close()
        assertEquals(7L, client.observedAfterSequence)
        assertEquals(RuntimeRunStatus.EXECUTING, controller.projection.value.runStatus)
        assertEquals(8L, controller.projection.value.lastAppliedSequence)
    }

    @Test
    fun `late older event cannot regress a completed run to executing`() = runTest {
        val client = FakeRuntimeUiClient(
            SessionProjection(
                sessionId = "session-1",
                runId = "run-1",
                lastAppliedSequence = 7,
                revision = 7,
                runStatus = RuntimeRunStatus.EXECUTING,
            ),
        )
        val controller = AgentRuntimeProjectionController(client, "session-1", "compose", this)
        controller.initialize()
        runCurrent()
        client.events.emit(
            RuntimeUiEvent.RunStatusChanged(
                "session-1",
                "run-1",
                sequence = 9,
                revision = 9,
                status = RuntimeRunStatus.SUCCEEDED,
            ),
        )
        client.events.emit(
            RuntimeUiEvent.RunStatusChanged(
                "session-1",
                "run-1",
                sequence = 8,
                revision = 8,
                status = RuntimeRunStatus.EXECUTING,
            ),
        )
        runCurrent()

        assertEquals(RuntimeRunStatus.SUCCEEDED, controller.projection.value.runStatus)
        assertEquals(9L, controller.projection.value.lastAppliedSequence)
        controller.close()
    }

    @Test
    fun `pending card resolves its redacted body from the staged candidate`() = runTest {
        // Live path: ApprovalRequested carries no body (journal stays redacted), only an opaque
        // candidateId. The controller must fill the card's details from the staging area.
        val approval = PendingApprovalProjection(
            proposalId = "proposal-1",
            payloadRef = "payload-ref-1",
            title = "保存一条preference记忆",
            candidateId = "candidate-1",
        )
        val client = FakeRuntimeUiClient(
            SessionProjection(
                sessionId = "session-1",
                runId = "run-1",
                revision = 5,
                runStatus = RuntimeRunStatus.AWAITING_CONFIRMATION,
                pendingApproval = approval,
                allowedActions = setOf(RuntimeAction.APPROVE, RuntimeAction.REJECT),
            ),
        ).apply { stagedContent["candidate-1"] = "用户喜欢简洁回答" }
        val controller = AgentRuntimeProjectionController(client, "session-1", "compose", this, idFactory = { "id" })

        controller.initialize()
        runCurrent()

        assertEquals("用户喜欢简洁回答", controller.projection.value.pendingApproval?.details)
        controller.close()
    }

    @Test
    fun `pending card resolves sensitive fields from encrypted approval staging`() = runTest {
        val approval = PendingApprovalProjection(
            proposalId = "proposal-1",
            payloadRef = "payload-ref-1",
            title = "确认发送内容",
            stagedContentRef = "staged-approval-1",
        )
        val client = FakeRuntimeUiClient(
            SessionProjection(
                sessionId = "session-1",
                runId = "run-1",
                revision = 5,
                runStatus = RuntimeRunStatus.AWAITING_CONFIRMATION,
                pendingApproval = approval,
                allowedActions = setOf(RuntimeAction.APPROVE, RuntimeAction.REJECT),
            ),
        ).apply {
            stagedApprovals["staged-approval-1"] = StagedApprovalContent(
                title = "打开短信发送消息",
                platform = "SMS",
                recipient = "13800000000",
                message = "请确认明天的会议",
            )
        }
        val controller = AgentRuntimeProjectionController(client, "session-1", "compose", this)

        controller.initialize()
        runCurrent()

        val resolved = requireNotNull(controller.projection.value.pendingApproval)
        assertEquals("打开短信发送消息", resolved.title)
        assertEquals("13800000000", resolved.recipient)
        assertEquals("请确认明天的会议", resolved.message)
        controller.close()
    }

    @Test
    fun `start dispatch carries current revision surface and stable action id`() = runTest {
        val client = FakeRuntimeUiClient(SessionProjection(sessionId = "session-1", revision = 9))
        val ids = ArrayDeque(listOf("command-1", "action-1"))
        val controller = AgentRuntimeProjectionController(
            client = client,
            sessionId = "session-1",
            surfaceId = "compose",
            scope = this,
            idFactory = { ids.removeFirst() },
        )
        controller.initialize()
        runCurrent()

        val receipt = controller.start("input-ref")
        val command = client.commands.single() as RuntimeUiCommand.Start
        controller.close()

        assertEquals(CommandReceiptStatus.ACCEPTED, receipt.status)
        assertEquals(9L, command.expectedRevision)
        assertEquals("compose", command.surfaceId)
        assertEquals("command-1", command.commandId)
        assertEquals("action-1", command.clientActionId)
    }

    @Test
    fun `approve uses projected run proposal and revision`() = runTest {
        val projection = SessionProjection(
            sessionId = "session-1",
            runId = "run-1",
            revision = 5,
            runStatus = RuntimeRunStatus.AWAITING_CONFIRMATION,
            pendingApproval = com.zhiban.rebuild.runtime.spi.PendingApprovalProjection(
                "proposal-1",
                "payload-ref-1",
                "创建日程",
            ),
            allowedActions = setOf(RuntimeAction.APPROVE),
        )
        val client = FakeRuntimeUiClient(projection)
        val controller = AgentRuntimeProjectionController(
            client,
            "session-1",
            "compose",
            this,
            idFactory = { "id-${client.commands.size}" },
        )
        controller.initialize()
        runCurrent()

        controller.approve()
        val command = client.commands.single() as RuntimeUiCommand.RunAction
        controller.close()

        assertEquals(RuntimeAction.APPROVE, command.action)
        assertEquals("run-1", command.runId)
        assertEquals("proposal-1", command.proposalId)
        assertEquals("payload-ref-1", command.payloadRef)
        assertEquals(5L, command.expectedRevision)
    }

    @Test
    fun `action not allowed by projection is rejected before dispatch`() = runTest {
        val client = FakeRuntimeUiClient(SessionProjection(sessionId = "session-1"))
        val controller = AgentRuntimeProjectionController(
            client,
            "session-1",
            "compose",
            this,
            idFactory = { "id" },
        )
        controller.initialize()
        runCurrent()

        val result = runCatching { controller.retry() }
        controller.close()

        assertTrue(result.isFailure)
        assertTrue(client.commands.isEmpty())
    }

    @Test
    fun `all six runtime commands carry projected revision without bypass`() = runTest {
        suspend fun commandFor(projection: SessionProjection, invoke: suspend (AgentRuntimeProjectionController) -> Unit): RuntimeUiCommand {
            val client = FakeRuntimeUiClient(projection)
            val controller = AgentRuntimeProjectionController(
                client,
                "session-1",
                "compose",
                this,
                idFactory = { "id-${client.commands.size}" },
            )
            controller.initialize()
            runCurrent()
            invoke(controller)
            controller.close()
            return client.commands.single()
        }

        val start = commandFor(SessionProjection("session-1", revision = 7)) { it.start("input-ref") }
        val approval = PendingApprovalProjection("proposal-1", "payload-ref-1", "创建日程")
        val approve = commandFor(active(RuntimeAction.APPROVE).copy(pendingApproval = approval)) { it.approve() }
        val reject = commandFor(active(RuntimeAction.REJECT).copy(pendingApproval = approval)) { it.reject() }
        val cancel = commandFor(active(RuntimeAction.CANCEL)) { it.cancel() }
        val retry = commandFor(active(RuntimeAction.RETRY)) { it.retry() }
        val resume = commandFor(active(RuntimeAction.RESUME)) { it.resume() }
        val undo = commandFor(
            active(RuntimeAction.UNDO).copy(
                runStatus = RuntimeRunStatus.SUCCEEDED,
                lastChangeId = "change-1",
                undoAvailable = true,
            ),
        ) { it.undo() }

        assertTrue(start is RuntimeUiCommand.Start)
        assertEquals(
            listOf(
                RuntimeAction.APPROVE,
                RuntimeAction.REJECT,
                RuntimeAction.CANCEL,
                RuntimeAction.RETRY,
                RuntimeAction.RESUME,
                RuntimeAction.UNDO,
            ),
            listOf(approve, reject, cancel, retry, resume, undo).map { (it as RuntimeUiCommand.RunAction).action },
        )
        assertTrue(listOf(start, approve, reject, cancel, retry, resume, undo).all { it.expectedRevision == 7L })
        assertTrue(
            listOf(approve, reject).all {
                (it as RuntimeUiCommand.RunAction).proposalId == "proposal-1" && it.payloadRef == "payload-ref-1"
            },
        )
        assertEquals("change-1", (undo as RuntimeUiCommand.RunAction).payloadRef)
    }

    private fun active(action: RuntimeAction) = SessionProjection(
        sessionId = "session-1",
        runId = "run-1",
        revision = 7,
        runStatus = when (action) {
            RuntimeAction.APPROVE, RuntimeAction.REJECT -> RuntimeRunStatus.AWAITING_CONFIRMATION
            RuntimeAction.RETRY -> RuntimeRunStatus.FAILED_RETRYABLE
            else -> RuntimeRunStatus.EXECUTING
        },
        recoveryNeeded = action == RuntimeAction.RESUME,
        allowedActions = setOf(action),
    )
}

private class FakeRuntimeUiClient(private val initial: SessionProjection) : RuntimeUiClient {
    val commands = mutableListOf<RuntimeUiCommand>()
    val events = MutableSharedFlow<RuntimeUiEvent>(extraBufferCapacity = 8)
    val stagedContent = mutableMapOf<String, String>()
    val stagedApprovals = mutableMapOf<String, StagedApprovalContent>()
    var observedAfterSequence: Long? = null
    var remainingSnapshotFailures = 0
    var snapshotReads = 0

    override suspend fun dispatch(command: RuntimeUiCommand): CommandReceipt {
        commands += command
        return CommandReceipt(CommandReceiptStatus.ACCEPTED, command.commandId, initial.revision)
    }

    override suspend fun getSessionProjection(sessionId: String): SessionProjection {
        snapshotReads++
        if (remainingSnapshotFailures-- > 0) error("synthetic snapshot failure")
        return initial
    }

    override fun observeSession(sessionId: String, afterSequenceExclusive: Long): Flow<RuntimeUiEvent> {
        observedAfterSequence = afterSequenceExclusive
        return events
    }

    override suspend fun stagedCandidateContent(candidateId: String): String? = stagedContent[candidateId]

    override suspend fun stagedApprovalContent(stagedRef: String): StagedApprovalContent? = stagedApprovals[stagedRef]
}
