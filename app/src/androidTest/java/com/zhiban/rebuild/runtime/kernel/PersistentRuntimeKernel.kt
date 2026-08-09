package com.zhiban.rebuild.runtime.kernel

import com.zhiban.rebuild.runtime.spi.RuntimeRunStatus
import com.zhiban.rebuild.runtime.store.RoomRuntimeStore

/** Test fixture for exercising the persisted state machine through the real store. */
internal class PersistentRuntimeKernel(private val store: RoomRuntimeStore) {
    suspend fun transition(runId: String, signal: RuntimeSignal, ownerId: String, fencingEpoch: Long, nowEpochMs: Long) {
        val current = requireNotNull(store.runById(runId))
        val currentStatus = RuntimeRunStatus.valueOf(current.status)
        val target = RuntimeStateMachine.reduce(currentStatus, signal)
        val (eventType, attemptTerminal) = eventFor(signal)
        store.transitionRun(
            runId = runId,
            expectedStatus = currentStatus.name,
            targetStatus = target.name,
            eventType = eventType,
            attemptTerminalStatus = attemptTerminal,
            ownerId = ownerId,
            fencingEpoch = fencingEpoch,
            nowEpochMs = nowEpochMs,
        )
    }

    private fun eventFor(signal: RuntimeSignal): Pair<String, String?> = when (signal) {
        RuntimeSignal.BeginContext -> "ContextAssemblyStarted" to null
        RuntimeSignal.ContextReady -> "ProviderAttemptStarted" to null
        RuntimeSignal.ModelReady -> "PlanProposed" to null
        RuntimeSignal.ModelResponseCompleted -> "RunCompleted" to "SUCCEEDED"
        RuntimeSignal.PlanValidated -> "ApprovalRequested" to null
        RuntimeSignal.Approved -> "ToolExecutionStarted" to null
        RuntimeSignal.Rejected -> "RunRejected" to null
        RuntimeSignal.ToolCompleted -> "ToolSucceeded" to "SUCCEEDED"
        RuntimeSignal.ObservationCompleted, RuntimeSignal.ExecutionCommitted -> "RunCompleted" to null
        RuntimeSignal.Cancel -> "RunCancelRequested" to null
        RuntimeSignal.RetryableFailure -> "RunFailedRetryable" to "FAILED"
        RuntimeSignal.FinalFailure -> "RunFailed" to "FAILED"
        RuntimeSignal.Retry -> "RunRetryStarted" to null
    }
}
