package com.zhiban.rebuild.ui.tabs

import org.junit.Assert.assertEquals
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
}
