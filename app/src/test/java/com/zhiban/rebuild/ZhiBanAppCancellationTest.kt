package com.zhiban.rebuild

import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ZhiBanAppCancellationTest {
    @Test
    fun runtimeRecoveryStartsBeforePotentiallyBlockingMaintenance() {
        val order = mutableListOf<String>()

        startRuntimeBeforeMaintenance(
            startRuntime = { order += "runtime" },
            startMaintenance = { order += "maintenance" },
        )

        assertEquals(listOf("runtime", "maintenance"), order)
    }

    @Test
    fun startupActionPropagatesCoroutineCancellation() = runBlocking {
        val cancellation = CancellationException("app startup cancelled")

        val observed = runCatching {
            runStartupAction<Unit> { throw cancellation }
        }.exceptionOrNull()

        assertSame(cancellation, observed)
    }
}
