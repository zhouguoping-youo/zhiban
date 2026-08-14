package com.zhiban.rebuild.ui.tabs

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CapturedResourceDisposerTest {
    @Test
    fun disposingAnOldEffectNeverReadsOrClosesANewerResource() {
        val disposed = mutableListOf<String>()
        var current: String? = null
        val emptyEffect = capturedResourceDisposer(current, disposed::add)

        current = "new-recorder"
        emptyEffect()

        assertEquals(emptyList<String>(), disposed)
        assertEquals("new-recorder", current)
    }

    @Test
    fun effectDisposesExactlyTheResourceItCaptured() {
        val disposed = mutableListOf<String>()
        val oldEffect = capturedResourceDisposer("old-recorder", disposed::add)

        oldEffect()

        assertEquals(listOf("old-recorder"), disposed)
    }

    @Test
    fun failedResourceStartReleasesTheConstructedResourceExactlyOnce() {
        var releases = 0

        val result = acquireStartedResource(
            create = { "recorder" },
            start = { throw IllegalStateException("prepare failed") },
            release = { releases += 1 },
        )

        assertTrue(result.isFailure)
        assertEquals(1, releases)
    }

    @Test
    fun cancellationDuringResourceStartReleasesAndPropagatesCancellation() {
        var releases = 0
        val cancellation = CancellationException("cancelled")

        val thrown = runCatching {
            acquireStartedResource(
                create = { "recorder" },
                start = { throw cancellation },
                release = { releases += 1 },
            )
        }.exceptionOrNull()

        assertSame(cancellation, thrown)
        assertEquals(1, releases)
    }
}
