package com.zhiban.rebuild.runtime.input

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppPrivateAttachmentStagerTest {
    private val root = Files.createTempDirectory("zhiban-input-test").toFile()
    private val png =
        byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) + ByteArray(24) { it.toByte() }
    private val limits = InputLimits(2, 64, 100, 5_000, setOf("image/png"))

    @After fun cleanup() {
        root.deleteRecursively()
    }

    @Test fun streamingCopyHashesAndCanBeDiscarded() = runBlocking {
        val stager = AppPrivateAttachmentStager.forTest(root, source("image/png", png), limits, nowEpochMs = { 1_000 })
        val staged = stager.stage("session", "content://image", 2_000)
        assertEquals("image/png", staged.mimeType)
        assertEquals(png.size.toLong(), staged.byteLength)
        assertEquals(64, staged.sha256Digest.length)
        val file = stager.resolveForTest(staged.contentRef)
        assertTrue(file.exists())
        assertTrue(file.readBytes().contentEquals(png))
        stager.discard(staged.attachmentId)
        assertFalse(file.exists())
    }

    @Test fun missingProviderSizeStillStagesWithinStreamingLimits() = runBlocking {
        val unknownSizeSource = object : AttachmentContentSource {
            override fun declaredMimeType(contentRef: String) = "image/png"
            override fun byteLength(contentRef: String): Long? = null
            override fun durationMs(contentRef: String) = null
            override fun open(contentRef: String): InputStream = ByteArrayInputStream(png)
        }
        val stager = AppPrivateAttachmentStager.forTest(
            root,
            unknownSizeSource,
            limits,
            nowEpochMs = { 1_000 },
        )

        val staged = stager.stage("session", "content://cloud-provider/image", 2_000)

        assertEquals(png.size.toLong(), staged.byteLength)
        assertTrue(stager.resolveForTest(staged.contentRef).isFile)
    }

    @Test fun detectsWebpImagesAcceptedByTheProviderContract() {
        val webp = "RIFF".toByteArray() + byteArrayOf(0, 0, 0, 0) + "WEBPVP8 ".toByteArray()
        assertEquals("image/webp", MagicMimeDetector.detect(webp))
    }

    @Test fun oversizeAndMimeMismatchDeletePartialFiles() = runBlocking {
        val oversize = AppPrivateAttachmentStager.forTest(
            root,
            source(
                "image/png",
                png + ByteArray(64),
            ),
            limits,
            nowEpochMs = {
                1_000
            },
        )
        assertTrue(runCatching { oversize.stage("session", "content://large", 2_000) }.isFailure)
        assertEquals(emptyList<String>(), root.list()?.toList())

        val mismatch = AppPrivateAttachmentStager.forTest(root, source("image/jpeg", png), limits, nowEpochMs = {
            1_000
        })
        assertTrue(runCatching { mismatch.stage("session", "content://spoof", 2_000) }.isFailure)
        assertEquals(emptyList<String>(), root.list()?.toList())
    }

    @Test fun readFailureDeletesPartialFile() = runBlocking {
        val broken = object : AttachmentContentSource {
            override fun declaredMimeType(contentRef: String) = "image/png"
            override fun byteLength(contentRef: String) = png.size.toLong()
            override fun durationMs(contentRef: String) = null
            override fun open(contentRef: String): InputStream = object : InputStream() {
                var index = 0
                override fun read(): Int {
                    if (index == 12) throw IOException("broken")
                    return if (index < png.size) png[index++].toInt() and 0xff else -1
                }
            }
        }
        val stager = AppPrivateAttachmentStager.forTest(root, broken, limits, nowEpochMs = { 1_000 })
        assertTrue(runCatching { stager.stage("session", "content://broken", 2_000) }.isFailure)
        assertEquals(emptyList<String>(), root.list()?.toList())
    }

    @Test fun expirySweepDeletesOnlyExpiredStaging() = runBlocking {
        var now = 1_000L
        val stager = AppPrivateAttachmentStager.forTest(root, source("image/png", png), limits, nowEpochMs = { now })
        val expired = stager.stage("session", "content://one", 1_100)
        val live = stager.stage("session", "content://two", 2_000)
        now = 1_101
        assertEquals(1, stager.purgeExpired())
        assertFalse(stager.resolveForTest(expired.contentRef).exists())
        assertTrue(stager.resolveForTest(live.contentRef).exists())
    }

    @Test fun cancellationBetweenChunksDeletesPartialFile() = runBlocking {
        val total = 512 * 1024L
        val slow = object : AttachmentContentSource {
            override fun declaredMimeType(contentRef: String) = "image/png"
            override fun byteLength(contentRef: String) = total
            override fun durationMs(contentRef: String) = null
            override fun open(contentRef: String): InputStream = object : InputStream() {
                var emitted = 0L
                override fun read(): Int = error("buffered read required")
                override fun read(target: ByteArray, offset: Int, length: Int): Int {
                    if (emitted >= total) return -1
                    Thread.sleep(5)
                    val count = minOf(length, 1024, (total - emitted).toInt())
                    repeat(count) { index ->
                        target[offset + index] =
                            if (emitted + index < png.size) png[(emitted + index).toInt()] else 0
                    }
                    emitted += count
                    return count
                }
            }
        }
        val largeLimits = limits.copy(maxPerItemBytes = total, maxAggregateBytes = total)
        val stager = AppPrivateAttachmentStager.forTest(root, slow, largeLimits, nowEpochMs = { 1_000 })
        val job = launch { stager.stage("session", "content://slow", 2_000) }
        delay(20)
        job.cancelAndJoin()
        assertEquals(emptyList<String>(), root.list()?.toList())
    }

    @Test fun concurrentStagesCannotBypassAggregateLedger() = runBlocking {
        val bytes = png.copyOf(8)
        val stager = AppPrivateAttachmentStager.forTest(
            root,
            source(
                "image/png",
                bytes,
            ),
            limits.copy(maxPerItemBytes = 8, maxAggregateBytes = 12),
            nowEpochMs = {
                1_000
            },
        )
        val results = awaitAll(
            async { runCatching { stager.stage("same-session", "content://one", 2_000) } },
            async { runCatching { stager.stage("same-session", "content://two", 2_000) } },
        )
        assertEquals(1, results.count { it.isSuccess })
        assertEquals(1, root.listFiles().orEmpty().count { it.extension == "bin" })
    }

    @Test fun audioWithoutDurationFailsClosedAndLeavesNoFile() = runBlocking {
        val wav = "RIFFxxxxWAVEdata".toByteArray()
        val audioLimits = limits.copy(maxPerItemBytes = 64, allowedMimeTypes = setOf("audio/wav"))
        val stager = AppPrivateAttachmentStager.forTest(root, source("audio/wav", wav), audioLimits, nowEpochMs = {
            1_000
        })
        assertTrue(runCatching { stager.stage("session", "content://audio", 2_000) }.isFailure)
        assertEquals(emptyList<String>(), root.list()?.toList())
    }

    @Test fun aggregateOverflowAndMalformedLedgerFileFailClosed() = runBlocking {
        val bytes = png.copyOf(8)
        val hugeLimits = limits.copy(maxPerItemBytes = 8, maxAggregateBytes = Long.MAX_VALUE)
        val stager = AppPrivateAttachmentStager.forTest(root, source("image/png", bytes), hugeLimits, nowEpochMs = {
            1_000
        })
        stager.stage("session", "content://one", 2_000)
        assertTrue(runCatching { StagingMath.add(Long.MAX_VALUE, 1) }.isFailure)
        File(root, "zbi_corrupt.bin").writeText("corrupt")
        assertTrue(runCatching { stager.stage("session", "content://three", 2_000) }.isFailure)
        assertEquals(2, root.listFiles().orEmpty().size)
    }

    private fun source(mime: String, bytes: ByteArray) = object : AttachmentContentSource {
        override fun declaredMimeType(contentRef: String) = mime
        override fun byteLength(contentRef: String) = bytes.size.toLong()
        override fun durationMs(contentRef: String) = null
        override fun open(contentRef: String): InputStream = ByteArrayInputStream(bytes)
    }
}
