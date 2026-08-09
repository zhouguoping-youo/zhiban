package com.zhiban.rebuild.runtime.kernel

import com.zhiban.rebuild.runtime.spi.RuntimeRunStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeStateMachineTest {
    @Test
    fun acceptedPathReachesSucceeded() {
        val path = listOf(
            RuntimeRunStatus.RECEIVED,
            RuntimeRunStatus.ASSEMBLING_CONTEXT,
            RuntimeRunStatus.INFERENCING,
            RuntimeRunStatus.VALIDATING_PLAN,
            RuntimeRunStatus.AWAITING_CONFIRMATION,
            RuntimeRunStatus.EXECUTING,
            RuntimeRunStatus.OBSERVING,
            RuntimeRunStatus.SUCCEEDED,
        )
        assertTrue(path.zipWithNext().all { (from, to) -> RuntimeStateMachine.canTransition(from, to) })
    }

    @Test
    fun cannotSkipConfirmationToExecute() {
        assertFalse(RuntimeStateMachine.canTransition(RuntimeRunStatus.VALIDATING_PLAN, RuntimeRunStatus.EXECUTING))
    }

    @Test
    fun cancelDuringExecutingRecordsRequestAndAllowsFactToWin() {
        assertEquals(
            RuntimeRunStatus.CANCEL_REQUESTED,
            RuntimeStateMachine.reduce(RuntimeRunStatus.EXECUTING, RuntimeSignal.Cancel),
        )
        assertEquals(
            RuntimeRunStatus.SUCCEEDED,
            RuntimeStateMachine.reduce(RuntimeRunStatus.CANCEL_REQUESTED, RuntimeSignal.ExecutionCommitted),
        )
    }

    @Test(expected = IllegalStateException::class)
    fun terminalRunRejectsRetryWithoutRetryableFailure() {
        RuntimeStateMachine.reduce(RuntimeRunStatus.SUCCEEDED, RuntimeSignal.Retry)
    }

    @Test
    fun rejectFromAwaitingConfirmationCancels() {
        assertEquals(
            RuntimeRunStatus.CANCELLED,
            RuntimeStateMachine.reduce(RuntimeRunStatus.AWAITING_CONFIRMATION, RuntimeSignal.Rejected),
        )
    }

    // Regression for the confirmation-card "拒绝" deadlock (#17): a run awaiting confirmation can slide
    // to a cancellation terminal while the user reads the card (provider idle-timeout cancels the attempt).
    // Rejecting then must still resolve to CANCELLED, not throw → POLICY_REJECTED → stuck card.
    @Test
    fun rejectFromCancellationTerminalIsIdempotent() {
        assertEquals(
            RuntimeRunStatus.CANCELLED,
            RuntimeStateMachine.reduce(RuntimeRunStatus.CANCELLED, RuntimeSignal.Rejected),
        )
        assertEquals(
            RuntimeRunStatus.CANCELLED,
            RuntimeStateMachine.reduce(RuntimeRunStatus.CANCEL_REQUESTED, RuntimeSignal.Rejected),
        )
    }

    @Test(expected = IllegalStateException::class)
    fun rejectFromSucceededStillIllegal() {
        RuntimeStateMachine.reduce(RuntimeRunStatus.SUCCEEDED, RuntimeSignal.Rejected)
    }
}
