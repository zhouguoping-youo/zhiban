package com.zhiban.rebuild.data.common

import com.zhiban.rebuild.foundation.runSuspendCatching
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Lazily starts one consumer for best-effort background sweeps. Signals are conflated while a
 * sweep is waiting or running, and an ordinary sweep failure does not terminate later sweeps.
 * Cancellation is still propagated by [runSuspendCatching].
 */
internal class ConflatedDebouncedTrigger(
    private val scope: CoroutineScope,
    private val debounceMs: Long,
    private val onFailure: (Throwable) -> Unit,
    private val action: suspend () -> Unit,
) {
    private val signals = Channel<Unit>(capacity = Channel.CONFLATED)

    @Volatile
    private var consumerStarted = false

    fun signal() {
        ensureConsumerStarted()
        signals.trySend(Unit)
    }

    @Synchronized
    private fun ensureConsumerStarted() {
        if (consumerStarted) return
        consumerStarted = true
        scope.launch {
            for (signal in signals) {
                delay(debounceMs)
                runSuspendCatching(action).onFailure(onFailure)
            }
        }
    }
}
