package com.zhiban.rebuild.ui.agent.projection

import com.zhiban.rebuild.runtime.spi.BudgetProjection
import com.zhiban.rebuild.runtime.spi.PendingApprovalProjection
import com.zhiban.rebuild.runtime.spi.PendingUserOperationProjection
import com.zhiban.rebuild.runtime.spi.RuntimeAction
import com.zhiban.rebuild.runtime.spi.RuntimeRunStatus
import com.zhiban.rebuild.runtime.spi.RuntimeUiEvent
import com.zhiban.rebuild.runtime.spi.SessionProjection
import com.zhiban.rebuild.runtime.spi.SourceProjection
import com.zhiban.rebuild.runtime.spi.UserOperationResultProjection

class AgentSessionReducer {
    fun reduce(previous: SessionProjection, event: RuntimeUiEvent): SessionProjection {
        require(event.sessionId == previous.sessionId) { "Event session does not match projection" }
        if (event.sequence <= previous.lastAppliedSequence) return previous

        val base = previous.copy(
            runId = event.runId ?: previous.runId,
            lastAppliedSequence = event.sequence,
            revision = event.revision,
            recoveryNeeded = false,
        )
        return when (event) {
            is RuntimeUiEvent.JournalAdvanced -> base

            is RuntimeUiEvent.RunStatusChanged -> applyRunStatusChanged(base, previous, event)

            is RuntimeUiEvent.AssistantDelta -> applyAssistantDelta(base, previous, event)

            is RuntimeUiEvent.AssistantAttemptStarted -> base.copy(
                runStatus = RuntimeRunStatus.INFERENCING,
                assistantText = "",
                assistantFinal = false,
                appliedDeltaOrdinals = emptySet(),
                safeFailureCode = null,
                allowedActions = allowedActionsFor(RuntimeRunStatus.INFERENCING),
            )

            is RuntimeUiEvent.ApprovalRequested -> base.copy(
                runStatus = RuntimeRunStatus.AWAITING_CONFIRMATION,
                pendingApproval = PendingApprovalProjection(
                    event.proposalId,
                    event.payloadRef,
                    event.title,
                    event.platform,
                    event.recipient,
                    event.message,
                    event.scheduleStartAtEpochMs,
                    event.scheduleDurationMinutes,
                    event.scheduleReminderMinutesBefore,
                    event.scheduleNote,
                    // The ApprovalRequested payload stays redacted; use the snapshot-provided body.
                    event.details ?: previous.pendingDetails,
                    event.candidateId,
                ),
                pendingDetails = event.details ?: previous.pendingDetails,
                allowedActions = allowedActionsFor(RuntimeRunStatus.AWAITING_CONFIRMATION),
            )

            is RuntimeUiEvent.BudgetChanged -> base.copy(
                budget = BudgetProjection(event.usedTokens, event.maxTokens),
            )

            is RuntimeUiEvent.SourceAttached -> base.copy(
                sources = previous.sources.filterNot { it.sourceId == event.sourceId } +
                    SourceProjection(event.sourceId, event.label),
            )

            is RuntimeUiEvent.UnsupportedSchema -> base.copy(
                runStatus = RuntimeRunStatus.FAILED_FINAL,
                readOnly = true,
                allowedActions = emptySet(),
            )

            is RuntimeUiEvent.ProjectionDegraded -> base.copy(
                degradationReasons = previous.degradationReasons + event.reasonCode,
            )

            is RuntimeUiEvent.UserOperationRequested -> base.copy(
                pendingUserOperation = PendingUserOperationProjection(
                    requestId = event.requestId,
                    type = event.type,
                    expiresAtEpochMs = event.expiresAtEpochMs,
                ),
            )

            is RuntimeUiEvent.UserOperationResolved -> base.copy(
                pendingUserOperation = previous.pendingUserOperation
                    ?.takeUnless { it.requestId == event.requestId },
                lastUserOperationResult = UserOperationResultProjection(event.requestId, event.state),
            )

            is RuntimeUiEvent.ChangeCommitted -> base.copy(
                lastChangeId = event.changeId,
                undoAvailable = true,
                allowedActions = base.allowedActions + RuntimeAction.UNDO,
            )

            is RuntimeUiEvent.ChangeUndone -> base.copy(
                undoAvailable = false,
                allowedActions = base.allowedActions - RuntimeAction.UNDO,
            )
        }
    }

    private fun applyRunStatusChanged(base: SessionProjection, previous: SessionProjection, event: RuntimeUiEvent.RunStatusChanged): SessionProjection {
        val observing = event.status == RuntimeRunStatus.OBSERVING
        val awaitingConfirmation = event.status == RuntimeRunStatus.AWAITING_CONFIRMATION
        return base.copy(
            runStatus = event.status,
            safeFailureCode = event.safeFailureCode,
            // Observation is a new assistant attempt after a tool has completed. Keeping the
            // pre-tool stream here makes the live bubble concatenate planning/fallback text
            // with the verified result, and the persisted final turn then appears a second time.
            assistantText = if (observing) "" else base.assistantText,
            assistantFinal = if (observing) false else base.assistantFinal,
            appliedDeltaOrdinals = if (observing) emptySet() else base.appliedDeltaOrdinals,
            pendingApproval = if (awaitingConfirmation) base.pendingApproval else null,
            pendingDetails = if (awaitingConfirmation) base.pendingDetails else null,
            allowedActions = allowedActionsFor(event.status) +
                if (previous.undoAvailable) setOf(RuntimeAction.UNDO) else emptySet(),
        )
    }

    private fun applyAssistantDelta(base: SessionProjection, previous: SessionProjection, event: RuntimeUiEvent.AssistantDelta): SessionProjection {
        val key = "${event.attemptId}:${event.ordinal}"
        return if (key in previous.appliedDeltaOrdinals) {
            base
        } else {
            base.copy(
                assistantText = previous.assistantText + event.part,
                assistantFinal = event.final,
                appliedDeltaOrdinals = previous.appliedDeltaOrdinals + key,
            )
        }
    }
}

internal fun allowedActionsFor(status: RuntimeRunStatus, recoveryNeeded: Boolean = false): Set<RuntimeAction> {
    val normal = when (status) {
        RuntimeRunStatus.RECEIVED -> setOf(RuntimeAction.CANCEL)

        RuntimeRunStatus.ASSEMBLING_CONTEXT,
        RuntimeRunStatus.INFERENCING,
        RuntimeRunStatus.VALIDATING_PLAN,
        -> setOf(RuntimeAction.STEER, RuntimeAction.CANCEL)

        RuntimeRunStatus.AWAITING_CONFIRMATION ->
            setOf(RuntimeAction.APPROVE, RuntimeAction.REJECT, RuntimeAction.CANCEL)

        RuntimeRunStatus.EXECUTING,
        RuntimeRunStatus.OBSERVING,
        RuntimeRunStatus.CANCEL_REQUESTED,
        -> setOf(RuntimeAction.CANCEL)

        RuntimeRunStatus.FAILED_RETRYABLE -> setOf(RuntimeAction.RETRY, RuntimeAction.CANCEL)

        RuntimeRunStatus.SUCCEEDED,
        RuntimeRunStatus.CANCELLED,
        RuntimeRunStatus.FAILED_FINAL,
        -> setOf(
            RuntimeAction.START,
            RuntimeAction.FEEDBACK_POSITIVE,
            RuntimeAction.FEEDBACK_NEGATIVE,
        )
    }
    return if (recoveryNeeded &&
        status !in setOf(RuntimeRunStatus.SUCCEEDED, RuntimeRunStatus.CANCELLED, RuntimeRunStatus.FAILED_FINAL)
    ) {
        normal + RuntimeAction.RESUME
    } else {
        normal
    }
}
