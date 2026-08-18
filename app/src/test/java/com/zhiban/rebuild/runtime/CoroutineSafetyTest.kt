package com.zhiban.rebuild.runtime

import com.zhiban.rebuild.foundation.runSuspendCatching

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class CoroutineSafetyTest {
    @Test fun cancellationIsRethrownInsteadOfRenderedAsFailure() = runTest {
        val cancellation = CancellationException("test")
        val observed = runCatching { runSuspendCatching<String> { throw cancellation } }.exceptionOrNull()
        assertSame(cancellation, observed)
    }

    @Test fun ordinaryFailureRemainsAvailableForProductErrorHandling() = runTest {
        val result = runSuspendCatching<String> { error("failed") }
        assertEquals("failed", result.exceptionOrNull()?.message)
    }
}
