package com.zhiban.rebuild.provider

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow

/** Provider-neutral retry/circuit boundary. Streaming is retried only before the first event. */
class ResilientProviderAdapter(
    private val delegate: ProviderAdapter,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
    private val retryDelaysMs: List<Long> = listOf(1_000, 2_000, 4_000),
    private val failureThreshold: Int = 5,
    private val openDurationMs: Long = 60_000,
) : ProviderAdapter {
    private val circuit = CircuitBreaker(clock, failureThreshold, openDurationMs)

    override suspend fun probe(profile: ProviderProfile): CapabilitySnapshot = retrying {
        delegate.probe(profile)
    }

    override suspend fun probe(profile: ProviderProfile, requestId: String): CapabilitySnapshot = retrying {
        delegate.probe(profile, requestId)
    }

    override fun stream(request: ModelRequest): Flow<ModelEvent> = flow {
        var retry = 0
        while (true) {
            circuit.requireAvailable()
            var emitted = false
            try {
                delegate.stream(request).collect { event ->
                    emitted = true
                    emit(event)
                }
                circuit.recordSuccess()
                break
            } catch (cancelled: CancellationException) {
                delegate.cancel(request.requestId)
                throw cancelled
            } catch (failure: Throwable) {
                delegate.cancel(request.requestId)
                if (emitted || !retryable(failure) || retry >= retryDelaysMs.size) {
                    circuit.recordFailure(failure)
                    throw failure
                }
                sleeper(retryDelaysMs[retry++])
            }
        }
    }

    override fun cancel(requestId: String): Boolean = delegate.cancel(requestId)

    private suspend fun <T> retrying(block: suspend () -> T): T {
        var retry = 0
        while (true) {
            circuit.requireAvailable()
            try {
                return block().also { circuit.recordSuccess() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (!retryable(failure) || retry >= retryDelaysMs.size) {
                    circuit.recordFailure(failure)
                    throw failure
                }
                sleeper(retryDelaysMs[retry++])
            }
        }
    }

    private fun retryable(failure: Throwable): Boolean = when (failure) {
        is ProviderFailure -> failure.retryable
        is IOException -> true
        else -> false
    }
}

private class CircuitBreaker(private val clock: () -> Long, private val threshold: Int, private val openDurationMs: Long) {
    private var consecutiveFailures = 0
    private var openUntil = 0L

    @Synchronized fun requireAvailable() {
        if (openUntil > clock()) throw ProviderFailure("PROVIDER_CIRCUIT_OPEN", true, openUntil - clock())
        if (openUntil != 0L) {
            openUntil = 0
            consecutiveFailures = 0
        }
    }

    @Synchronized fun recordSuccess() {
        consecutiveFailures = 0
        openUntil = 0
    }

    @Synchronized fun recordFailure(failure: Throwable) {
        val isTransient = (failure as? ProviderFailure)?.retryable == true || failure is IOException
        if (!isTransient) return
        consecutiveFailures++
        if (consecutiveFailures >= threshold) openUntil = clock() + openDurationMs
    }
}
