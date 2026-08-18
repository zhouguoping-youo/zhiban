package com.zhiban.rebuild.ui.agent.projection

import android.util.Log
import com.zhiban.rebuild.runtime.spi.CommandReceipt
import com.zhiban.rebuild.runtime.spi.PendingApprovalProjection
import com.zhiban.rebuild.runtime.spi.RuntimeAction
import com.zhiban.rebuild.runtime.spi.RuntimeUiClient
import com.zhiban.rebuild.runtime.spi.RuntimeUiCommand
import com.zhiban.rebuild.runtime.spi.SessionProjection
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AgentRuntimeProjectionController(
    private val client: RuntimeUiClient,
    private val sessionId: String,
    private val surfaceId: String,
    private val scope: CoroutineScope,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val reducer: AgentSessionReducer = AgentSessionReducer(),
    private val reconnectDelayMs: Long = PROJECTION_RECONNECT_DELAY_MS,
) {
    private val mutableProjection = MutableStateFlow(SessionProjection(sessionId = sessionId))
    val projection: StateFlow<SessionProjection> = mutableProjection.asStateFlow()
    private var observation: Job? = null

    fun initialize() {
        if (observation != null) return
        observation = scope.launch {
            while (true) {
                currentCoroutineContext().ensureActive()
                try {
                    val snapshot = normalizedSnapshot(client.getSessionProjection(sessionId))
                    mutableProjection.value = snapshot.withResolvedApprovalDetails()
                    client.observeSession(sessionId, snapshot.lastAppliedSequence).collect { event ->
                        val current = mutableProjection.value
                        // The durable journal can briefly contain a terminal event while the compact
                        // snapshot still points at the preceding EXECUTING watermark. Once that
                        // terminal event is reduced, never let an older/non-terminal event from a
                        // delayed Room emission regress the live UI back to an un-cancellable
                        // "正在完成操作" state.
                        if (event.sequence > current.lastAppliedSequence) {
                            mutableProjection.value = reducer.reduce(current, event)
                                .withResolvedApprovalDetails()
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    // M2:投影循环失败记原因码——可区分"没事件"与"投影已死",不再静默重试。
                    Log.w(PROJECTION_CONTROLLER_TAG, "projection:loop_failure", failure)
                    mutableProjection.value = projectionFailureFallback(mutableProjection.value)
                }
                delay(reconnectDelayMs)
            }
        }
    }

    private fun normalizedSnapshot(rawSnapshot: SessionProjection): SessionProjection {
        val recoveryNeeded =
            rawSnapshot.runId != null && rawSnapshot.runStatus !in TERMINAL_STATUSES && !rawSnapshot.readOnly
        return rawSnapshot.copy(
            recoveryNeeded = recoveryNeeded,
            allowedActions = allowedActionsFor(rawSnapshot.runStatus, recoveryNeeded) +
                if (rawSnapshot.undoAvailable) setOf(RuntimeAction.UNDO) else emptySet(),
        )
    }

    private fun projectionFailureFallback(previous: SessionProjection): SessionProjection = previous.copy(
        runStatus = com.zhiban.rebuild.runtime.spi.RuntimeRunStatus.FAILED_RETRYABLE,
        safeFailureCode = PROJECTION_UNAVAILABLE,
        allowedActions = setOf(RuntimeAction.START),
        recoveryNeeded = false,
    )

    // The confirmation card's body (e.g. memory content) is redacted from the durable event journal.
    // When a card becomes pending carrying only an opaque candidateId, resolve the body from the
    // short-lived staging area in-memory so the live path shows it too — not just on reconnect replay.
    private suspend fun SessionProjection.withResolvedApprovalDetails(): SessionProjection {
        var resolvedProjection = this
        var approval = resolvedProjection.pendingApproval ?: return this
        val staged = approval.stagedContentRef?.let { client.stagedApprovalContent(it) }
        if (staged != null) {
            approval = approval.withStagedContent(staged)
            resolvedProjection = resolvedProjection.copy(pendingApproval = approval)
        }
        if (approval.details != null) return resolvedProjection
        val candidateId = approval.candidateId ?: return resolvedProjection
        val resolved = client.stagedCandidateContent(candidateId) ?: return resolvedProjection
        return resolvedProjection.copy(pendingApproval = approval.copy(details = resolved))
    }

    private fun PendingApprovalProjection.withStagedContent(staged: com.zhiban.rebuild.runtime.spi.StagedApprovalContent): PendingApprovalProjection = copy(
        title = staged.title ?: title,
        platform = staged.platform ?: platform,
        recipient = staged.recipient ?: recipient,
        message = staged.message ?: message,
        scheduleStartAtEpochMs = staged.scheduleStartAtEpochMs ?: scheduleStartAtEpochMs,
        scheduleDurationMinutes = staged.scheduleDurationMinutes ?: scheduleDurationMinutes,
        scheduleReminderMinutesBefore = staged.scheduleReminderMinutesBefore ?: scheduleReminderMinutesBefore,
        scheduleNote = staged.scheduleNote ?: scheduleNote,
        details = staged.details ?: details,
    )

    fun close() {
        observation?.cancel()
        observation = null
    }

    suspend fun start(inputRef: String): CommandReceipt {
        require(inputRef.isNotBlank()) { "inputRef must not be blank" }
        val current = mutableProjection.value
        return client.dispatch(
            RuntimeUiCommand.Start(
                sessionId = sessionId,
                inputRef = inputRef,
                commandId = idFactory(),
                clientActionId = idFactory(),
                expectedRevision = current.revision,
                surfaceId = surfaceId,
            ),
        )
    }

    suspend fun approve(): CommandReceipt = dispatchApprovalAction(
        action = RuntimeAction.APPROVE,
    )

    suspend fun reject(): CommandReceipt = dispatchApprovalAction(RuntimeAction.REJECT)
    suspend fun cancel(): CommandReceipt = dispatchAction(RuntimeAction.CANCEL)
    suspend fun retry(): CommandReceipt = dispatchAction(RuntimeAction.RETRY)
    suspend fun resume(): CommandReceipt = dispatchAction(RuntimeAction.RESUME)
    suspend fun undo(): CommandReceipt {
        val changeId = requireNotNull(mutableProjection.value.lastChangeId) { "No undoable change" }
        return dispatchAction(RuntimeAction.UNDO, payloadRef = changeId)
    }
    suspend fun positiveFeedback(): CommandReceipt = dispatchAction(RuntimeAction.FEEDBACK_POSITIVE)
    suspend fun negativeFeedback(): CommandReceipt = dispatchAction(RuntimeAction.FEEDBACK_NEGATIVE)

    suspend fun refresh() {
        val snapshot = client.getSessionProjection(sessionId)
        mutableProjection.value = snapshot.copy(
            allowedActions = allowedActionsFor(snapshot.runStatus, snapshot.recoveryNeeded) +
                if (snapshot.undoAvailable) setOf(RuntimeAction.UNDO) else emptySet(),
        ).withResolvedApprovalDetails()
    }

    private suspend fun dispatchApprovalAction(action: RuntimeAction): CommandReceipt {
        val approval = requireNotNull(mutableProjection.value.pendingApproval) { "No pending approval" }
        require(approval.payloadRef.isNotBlank()) { "Approval payload reference is unavailable" }
        return dispatchAction(action, approval.proposalId, approval.payloadRef)
    }

    suspend fun resolveUserOperation(
        requestId: String,
        result: com.zhiban.rebuild.runtime.spi.PendingUserOperationState,
        resultRef: String? = null,
    ): CommandReceipt {
        val current = mutableProjection.value
        val runId = requireNotNull(current.runId) { "No active run" }
        require(current.pendingUserOperation?.requestId == requestId) { "User operation is not pending" }
        return client.dispatch(
            RuntimeUiCommand.ResolveUserOperation(
                sessionId = sessionId,
                runId = runId,
                requestId = requestId,
                result = result,
                resultRef = resultRef,
                commandId = idFactory(),
                clientActionId = idFactory(),
                expectedRevision = current.revision,
                surfaceId = surfaceId,
            ),
        )
    }

    private suspend fun dispatchAction(action: RuntimeAction, proposalId: String? = null, payloadRef: String? = null): CommandReceipt {
        val current = mutableProjection.value
        require(action in current.allowedActions) { "$action is not allowed in ${current.runStatus}" }
        val runId = requireNotNull(current.runId) { "No active run" }
        return client.dispatch(
            RuntimeUiCommand.RunAction(
                action = action,
                sessionId = sessionId,
                runId = runId,
                proposalId = proposalId,
                payloadRef = payloadRef,
                commandId = idFactory(),
                clientActionId = idFactory(),
                expectedRevision = current.revision,
                surfaceId = surfaceId,
            ),
        )
    }
}

private val TERMINAL_STATUSES = setOf(
    com.zhiban.rebuild.runtime.spi.RuntimeRunStatus.SUCCEEDED,
    com.zhiban.rebuild.runtime.spi.RuntimeRunStatus.CANCELLED,
    com.zhiban.rebuild.runtime.spi.RuntimeRunStatus.FAILED_FINAL,
)

private const val PROJECTION_RECONNECT_DELAY_MS = 1_000L
private const val PROJECTION_UNAVAILABLE = "PROJECTION_UNAVAILABLE"

private const val PROJECTION_CONTROLLER_TAG = "AgentProjection"
