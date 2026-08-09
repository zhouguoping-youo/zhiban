package com.zhiban.rebuild.runtime.provider

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ResilientProviderAdapterTest {
    @Test fun probeRetriesTransientFailuresWithBoundedBackoff() = runBlocking {
        val fake = FakeAdapter(probeFailures = 2)
        val slept = mutableListOf<Long>()
        val resilient = ResilientProviderAdapter(fake, sleeper = { slept += it })

        assertEquals(capability(), resilient.probe(profile(), "health"))
        assertEquals(3, fake.probes)
        assertEquals(listOf(1_000L, 2_000L), slept)
    }

    @Test fun streamRetriesOnlyBeforeFirstEvent() = runBlocking {
        val fake = FakeAdapter(streamFailuresBeforeEvent = 1)
        val resilient = ResilientProviderAdapter(fake, sleeper = {})
        assertEquals(listOf(ModelEvent.Final("stop")), resilient.stream(request()).toList())
        assertEquals(2, fake.streams)

        val afterEvent = FakeAdapter(failAfterDelta = true)
        val failure = runCatching {
            ResilientProviderAdapter(afterEvent, sleeper = {}).stream(request()).toList()
        }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertEquals(1, afterEvent.streams)
    }

    @Test fun fatalDoesNotRetryAndCircuitOpensAfterRepeatedTransientFailures() = runBlocking {
        val fatal = FakeAdapter(fatalProbe = true)
        assertTrue(runCatching { ResilientProviderAdapter(fatal, sleeper = {}).probe(profile()) }.isFailure)
        assertEquals(1, fatal.probes)

        var now = 100L
        val transient = FakeAdapter(probeFailures = Int.MAX_VALUE)
        val resilient = ResilientProviderAdapter(
            transient,
            clock = { now },
            sleeper = {},
            retryDelaysMs = emptyList(),
            failureThreshold = 2,
            openDurationMs = 1_000,
        )
        repeat(2) { assertTrue(runCatching { resilient.probe(profile()) }.isFailure) }
        val open = runCatching { resilient.probe(profile()) }.exceptionOrNull() as ProviderFailure
        assertEquals("PROVIDER_CIRCUIT_OPEN", open.code)
        assertEquals(2, transient.probes)
        now += 1_001
        assertTrue(runCatching { resilient.probe(profile()) }.isFailure)
        assertEquals(3, transient.probes)
    }

    @Test fun cancellationIsNeverRetriedOrRecordedAsProviderFailure() = runBlocking {
        var cancellations = 0
        val cancelling = object : ProviderAdapter {
            override suspend fun probe(profile: ProviderProfile): CapabilitySnapshot = throw CancellationException("probe stopped")

            override fun stream(request: ModelRequest): Flow<ModelEvent> = flow {
                throw CancellationException("stream stopped")
            }

            override fun cancel(requestId: String): Boolean {
                cancellations++
                return true
            }
        }
        val resilient = ResilientProviderAdapter(cancelling, sleeper = { error("must not retry") })

        try {
            resilient.probe(profile())
            fail("probe cancellation must propagate")
        } catch (_: CancellationException) {
            // Expected.
        }
        try {
            resilient.stream(request()).toList()
            fail("stream cancellation must propagate")
        } catch (_: CancellationException) {
            // Expected.
        }
        assertEquals(1, cancellations)
    }

    private class FakeAdapter(
        private var probeFailures: Int = 0,
        private var streamFailuresBeforeEvent: Int = 0,
        private val failAfterDelta: Boolean = false,
        private val fatalProbe: Boolean = false,
    ) : ProviderAdapter {
        var probes = 0
        var streams = 0
        override suspend fun probe(profile: ProviderProfile): CapabilitySnapshot {
            probes++
            if (fatalProbe) throw ProviderFailure("AUTHENTICATION_FAILED", false)
            if (probeFailures-- > 0) throw IOException("temporary")
            return capability()
        }
        override fun stream(request: ModelRequest): Flow<ModelEvent> = flow {
            streams++
            if (streamFailuresBeforeEvent-- > 0) throw IOException("temporary")
            if (failAfterDelta) {
                emit(ModelEvent.Delta(0, "partial"))
                throw IOException("lost")
            }
            emit(ModelEvent.Final("stop"))
        }
        override fun cancel(requestId: String) = true
    }

    private companion object {
        fun profile() = ProviderProfile("stepfun", "chat", "step-3.5-flash", "credential", 1)
        fun capability() = CapabilitySnapshot("digest", setOf("text"), emptySet(), 8_000, 1_000, 0, Long.MAX_VALUE)
        fun request() = ModelRequest(
            "request",
            OutboundChannel.LLM_INFERENCE,
            profile(),
            emptyList(),
            capability(),
            100,
        )
    }
}
