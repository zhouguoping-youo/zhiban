package com.zhiban.rebuild.ui.settings

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeneralSettingsCacheTest {
    @Test
    fun clearingRegenerableCachePreservesActiveAndUnsentAttachments() {
        val cache = Files.createTempDirectory("zhiban-cache-test").toFile()
        try {
            val staged = File(cache, "zhiban-runtime-input/active.bin").apply {
                requireNotNull(parentFile).mkdirs()
                writeBytes(byteArrayOf(1, 2, 3))
            }
            val unsent = File(cache, "multimodal/capture.jpg").apply {
                requireNotNull(parentFile).mkdirs()
                writeBytes(byteArrayOf(4, 5))
            }
            val diagnostic = File(cache, "zhiban-diagnostics/report.zip").apply {
                requireNotNull(parentFile).mkdirs()
                writeBytes(byteArrayOf(6, 7, 8, 9))
            }

            assertEquals(4L, regenerableCacheSize(cache))
            clearRegenerableCache(cache)

            assertTrue(staged.isFile)
            assertTrue(unsent.isFile)
            assertFalse(diagnostic.exists())
            assertEquals(0L, regenerableCacheSize(cache))
        } finally {
            cache.deleteRecursively()
        }
    }
}
