package com.zhiban.rebuild.runtime.kernel

import com.zhiban.rebuild.runtime.runSuspendCatching
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** App-process lifecycle runner. It is not an Android Service and never depends on UI polling. */
@Singleton
internal class RuntimeCommandRunner @Inject constructor(private val processor: KernelCommandProcessor) {
    private val started = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        val signals = Channel<Unit>(Channel.CONFLATED)
        scope.launch { processor.observeWorkCount().collect { signals.trySend(Unit) } }
        scope.launch {
            signals.send(Unit)
            while (true) {
                signals.receive()
                var backoffMs = 100L
                while (true) {
                    val outcome = processNextSafely(processor::processNext)
                    when (outcome) {
                        KernelCommandProcessor.Outcome.PROCESSED -> backoffMs = 100L

                        KernelCommandProcessor.Outcome.IDLE -> break

                        KernelCommandProcessor.Outcome.FAILED -> {
                            delay(backoffMs)
                            backoffMs = (backoffMs * 2).coerceAtMost(5_000L)
                        }
                    }
                }
                val wakeInMs = processor.millisUntilNextLeaseExpiry()
                if (wakeInMs != null) {
                    withTimeoutOrNull(wakeInMs) { signals.receive() }
                    signals.trySend(Unit)
                }
            }
        }
    }

    internal fun stopForTest() = scope.cancel()
}

internal suspend fun processNextSafely(processNext: suspend () -> KernelCommandProcessor.Outcome): KernelCommandProcessor.Outcome =
    runSuspendCatching(processNext)
        .getOrDefault(KernelCommandProcessor.Outcome.FAILED)
