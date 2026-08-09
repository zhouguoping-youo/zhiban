package com.zhiban.rebuild.data.calllog

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallNoteAudioCacheTest {
    @Test
    fun purgesOnlyExpiredTemporaryRecordings() {
        val directory = Files.createTempDirectory("call-note-audio").toFile()
        try {
            val expired = directory.resolve("expired.ogg").apply {
                writeText("old")
                setLastModified(1_000)
            }
            val fresh = directory.resolve("fresh.ogg").apply {
                writeText("new")
                setLastModified(3_000)
            }

            assertEquals(1, purgeExpiredCallNoteAudio(directory, 2_000))
            assertFalse(expired.exists())
            assertTrue(fresh.exists())
        } finally {
            directory.deleteRecursively()
        }
    }
}
