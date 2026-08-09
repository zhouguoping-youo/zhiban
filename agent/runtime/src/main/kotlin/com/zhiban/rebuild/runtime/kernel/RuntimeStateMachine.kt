package com.zhiban.rebuild.runtime.kernel

import com.zhiban.rebuild.runtime.spi.RuntimeRunStatus

sealed interface RuntimeSignal {
    data object BeginContext : RuntimeSignal
    data object ContextReady : RuntimeSignal
    data object ModelReady : RuntimeSignal
    data object ModelResponseCompleted : RuntimeSignal
    data object PlanValidated : RuntimeSignal
    data object Approved : RuntimeSignal
    data object Rejected : RuntimeSignal
    data object ToolCompleted : RuntimeSignal
    data object ObservationCompleted : RuntimeSignal
    data object Cancel : RuntimeSignal
    data object ExecutionCommitted : RuntimeSignal
    data object RetryableFailure : RuntimeSignal
    data object FinalFailure : RuntimeSignal
    data object Retry : RuntimeSignal
}

object RuntimeStateMachine {
    private val allowed = mapOf(
        RuntimeRunStatus.RECEIVED to
            setOf(
                RuntimeRunStatus.ASSEMBLING_CONTEXT,
                RuntimeRunStatus.CANCEL_REQUESTED,
                RuntimeRunStatus.FAILED_FINAL,
            ),
        RuntimeRunStatus.ASSEMBLING_CONTEXT to
            setOf(
                RuntimeRunStatus.INFERENCING,
                RuntimeRunStatus.CANCEL_REQUESTED,
                RuntimeRunStatus.FAILED_RETRYABLE,
                RuntimeRunStatus.FAILED_FINAL,
            ),
        RuntimeRunStatus.INFERENCING to
            setOf(
                RuntimeRunStatus.VALIDATING_PLAN,
                RuntimeRunStatus.SUCCEEDED,
                RuntimeRunStatus.CANCEL_REQUESTED,
                RuntimeRunStatus.FAILED_RETRYABLE,
                RuntimeRunStatus.FAILED_FINAL,
            ),
        RuntimeRunStatus.VALIDATING_PLAN to
            setOf(
                RuntimeRunStatus.AWAITING_CONFIRMATION,
                RuntimeRunStatus.CANCEL_REQUESTED,
                RuntimeRunStatus.FAILED_FINAL,
            ),
        RuntimeRunStatus.AWAITING_CONFIRMATION to
            setOf(RuntimeRunStatus.EXECUTING, RuntimeRunStatus.CANCEL_REQUESTED, RuntimeRunStatus.CANCELLED),
        RuntimeRunStatus.EXECUTING to
            setOf(
                RuntimeRunStatus.OBSERVING,
                RuntimeRunStatus.CANCEL_REQUESTED,
                RuntimeRunStatus.FAILED_RETRYABLE,
                RuntimeRunStatus.FAILED_FINAL,
            ),
        RuntimeRunStatus.OBSERVING to
            setOf(
                RuntimeRunStatus.VALIDATING_PLAN,
                RuntimeRunStatus.SUCCEEDED,
                RuntimeRunStatus.CANCEL_REQUESTED,
                RuntimeRunStatus.FAILED_RETRYABLE,
                RuntimeRunStatus.FAILED_FINAL,
            ),
        RuntimeRunStatus.CANCEL_REQUESTED to
            setOf(RuntimeRunStatus.CANCELLED, RuntimeRunStatus.SUCCEEDED, RuntimeRunStatus.FAILED_FINAL),
        // Idempotent cancel target: a Rejected/late Cancel landing on an already-cancelled run is a
        // no-op to CANCELLED, not an illegal transition (keeps confirmation-card rejection deadlock-free).
        RuntimeRunStatus.CANCELLED to setOf(RuntimeRunStatus.CANCELLED),
        RuntimeRunStatus.FAILED_RETRYABLE to
            setOf(RuntimeRunStatus.INFERENCING, RuntimeRunStatus.EXECUTING, RuntimeRunStatus.CANCELLED),
    )

    fun canTransition(from: RuntimeRunStatus, to: RuntimeRunStatus): Boolean = allowed[from]?.contains(to) == true

    fun reduce(current: RuntimeRunStatus, signal: RuntimeSignal): RuntimeRunStatus {
        val target = when (signal) {
            RuntimeSignal.BeginContext -> RuntimeRunStatus.ASSEMBLING_CONTEXT

            RuntimeSignal.ContextReady -> RuntimeRunStatus.INFERENCING

            RuntimeSignal.ModelReady -> RuntimeRunStatus.VALIDATING_PLAN

            RuntimeSignal.ModelResponseCompleted -> RuntimeRunStatus.SUCCEEDED

            RuntimeSignal.PlanValidated -> RuntimeRunStatus.AWAITING_CONFIRMATION

            RuntimeSignal.Approved -> RuntimeRunStatus.EXECUTING

            RuntimeSignal.Rejected -> when (current) {
                RuntimeRunStatus.AWAITING_CONFIRMATION -> RuntimeRunStatus.CANCELLED

                // A pending confirmation can slide to a cancellation terminal while the user reads the
                // card (provider idle-timeout cancels the in-flight attempt, and a run awaiting input can
                // be cancelled). Rejecting such a run is still the user's cancel — accept it idempotently
                // so the confirmation card always resolves instead of deadlocking on POLICY_REJECTED.
                RuntimeRunStatus.CANCEL_REQUESTED,
                RuntimeRunStatus.CANCELLED,
                -> RuntimeRunStatus.CANCELLED

                else -> throw IllegalStateException("run is not awaiting confirmation")
            }

            RuntimeSignal.ToolCompleted -> RuntimeRunStatus.OBSERVING

            RuntimeSignal.ObservationCompleted, RuntimeSignal.ExecutionCommitted -> RuntimeRunStatus.SUCCEEDED

            RuntimeSignal.Cancel -> RuntimeRunStatus.CANCEL_REQUESTED

            RuntimeSignal.RetryableFailure -> RuntimeRunStatus.FAILED_RETRYABLE

            RuntimeSignal.FinalFailure -> RuntimeRunStatus.FAILED_FINAL

            RuntimeSignal.Retry -> if (current ==
                RuntimeRunStatus.FAILED_RETRYABLE
            ) {
                RuntimeRunStatus.INFERENCING
            } else {
                throw IllegalStateException("run is not retryable")
            }
        }
        check(canTransition(current, target)) { "illegal runtime transition: $current -> $target" }
        return target
    }
}
