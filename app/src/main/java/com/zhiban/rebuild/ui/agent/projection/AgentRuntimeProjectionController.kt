package com.zhiban.rebuild.ui.agent.projection

import com.zhiban.rebuild.runtime.spi.CommandReceipt
import com.zhiban.rebuild.runtime.spi.RuntimeAction
import com.zhiban.rebuild.runtime.spi.RuntimeUiClient
import com.zhiban.rebuild.runtime.spi.RuntimeUiCommand
import com.zhiban.rebuild.runtime.spi.SessionProjection
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
) {
    private val mutableProjection = MutableStateFlow(SessionProjection(sessionId = sessionId))
    val projection: StateFlow<SessionProjection> = mutableProjection.asStateFlow()
    private var observation: Job? = null

    fun initialize() {
        if (observation != null) return
        observation = scope.launch {
            val rawSnapshot = client.getSessionProjection(sessionId)
            val recoveryNeeded =
                rawSnapshot.runId != null && rawSnapshot.runStatus !in TERMINAL_STATUSES && !rawSnapshot.readOnly
            val snapshot = rawSnapshot.copy(
                recoveryNeeded = recoveryNeeded,
                allowedActions = allowedActionsFor(rawSnapshot.runStatus, recoveryNeeded) +
                    if (rawSnapshot.undoAvailable) setOf(RuntimeAction.UNDO) else emptySet(),
            )
            mutableProjection.value = snapshot.withResolvedApprovalDetails()
            client.observeSession(sessionId, snapshot.lastAppliedSequence).collect { event ->
                mutableProjection.value = reducer.reduce(mutableProjection.value, event).withResolvedApprovalDetails()
            }
        }
    }

    // The confirmation card's body (e.g. memory content) is redacted from the durable event journal.
    // When a card becomes pending carrying only an opaque candidateId, resolve the body from the
    // short-lived staging area in-memory so the live path shows it too — not just on reconnect replay.
    private suspend fun SessionProjection.withResolvedApprovalDetails(): SessionProjection {
        val approval = pendingApproval ?: return this
        if (approval.details != null) return this
        val candidateId = approval.candidateId ?: return this
        val resolved = client.stagedCandidateContent(candidateId) ?: return this
        return copy(pendingApproval = approval.copy(details = resolved))
    }

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
        )
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
