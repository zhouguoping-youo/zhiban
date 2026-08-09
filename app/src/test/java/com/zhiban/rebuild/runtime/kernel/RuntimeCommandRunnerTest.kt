package com.zhiban.rebuild.runtime.kernel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class RuntimeCommandRunnerTest {
    @Test
    fun `processing failure remains retryable runner failure`() = runTest {
        val outcome = processNextSafely { error("database unavailable") }

        assertEquals(KernelCommandProcessor.Outcome.FAILED, outcome)
    }

    @Test
    fun `processing cancellation is never converted into runner failure`() = runTest {
        try {
            processNextSafely { throw CancellationException("stopped") }
            fail("cancellation must propagate")
        } catch (_: CancellationException) {
            // Expected.
        }
    }
}
