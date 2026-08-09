package com.zhiban.rebuild.runtime.context

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

internal data class RetrievalAttempt<T>(val value: T?, val degradation: String?)

/** Keeps retrieval resilient without turning cancellation or private exception text into telemetry. */
internal suspend fun <T> attemptRetrieval(path: String, timeoutMs: Long? = null, block: suspend () -> T): RetrievalAttempt<T> {
    require(path.matches(Regex("[a-z0-9_]{2,64}")))
    require(timeoutMs == null || timeoutMs > 0)
    return try {
        val value = if (timeoutMs == null) block() else withTimeout(timeoutMs) { block() }
        RetrievalAttempt(value, null)
    } catch (_: TimeoutCancellationException) {
        RetrievalAttempt(null, "$path:timeout")
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        RetrievalAttempt(null, "$path:failure")
    }
}
