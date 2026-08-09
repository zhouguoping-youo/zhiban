package com.zhiban.rebuild.runtime.input

import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhiban.rebuild.runtime.provider.AppPrivateProviderAttachmentResolver
import com.zhiban.rebuild.runtime.provider.ModelAttachment
import com.zhiban.rebuild.runtime.provider.OutboundProvenance
import com.zhiban.rebuild.runtime.provider.OutboundPurpose
import com.zhiban.rebuild.runtime.provider.OutboundSensitivity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppPrivateAttachmentStagerInstrumentationTest {
    @Test fun stagedPdfRendersIntoBoundedVisionPages() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val document = PdfDocument()
        repeat(2) { index ->
            val page = document.startPage(PdfDocument.PageInfo.Builder(400, 600, index + 1).create())
            page.canvas.drawText("ZhiBan page ${index + 1}", 40f, 80f, Paint().apply { textSize = 24f })
            document.finishPage(page)
        }
        val output = ByteArrayOutputStream()
        document.writeTo(output)
        document.close()
        val pdf = output.toByteArray()
        val source = object : AttachmentContentSource {
            override fun declaredMimeType(contentRef: String) = "application/pdf"
            override fun byteLength(contentRef: String) = pdf.size.toLong()
            override fun durationMs(contentRef: String) = null
            override fun open(contentRef: String): InputStream = ByteArrayInputStream(pdf)
        }
        val stager = AppPrivateAttachmentStager.forProduction(
            context,
            source,
            InputLimits(1, 1_000_000, 1_000_000, 1_000, setOf("application/pdf")),
        )
        stager.rootForTest.deleteRecursively()
        stager.rootForTest.mkdirs()
        val ref = stager.stage("pdf-session", "content://pdf", System.currentTimeMillis() + 60_000)
        val pages = AppPrivateProviderAttachmentResolver(context).imageDataUrls(
            ModelAttachment(
                ref.attachmentId, ref.kind.name, ref.mimeType, ref.byteLength, ref.sha256Digest,
                ref.contentRef, ref.expiresAtEpochMs, OutboundSensitivity.SENSITIVE,
                OutboundPurpose.USER_SELECTED_ATTACHMENT, OutboundProvenance("test_attachment", ref.attachmentId),
            ),
            System.currentTimeMillis(),
        )
        assertTrue(pages.size == 2 && pages.all { it.startsWith("data:image/png;base64,") })
        pdf.fill(0)
        stager.rootForTest.deleteRecursively()
        Unit
    }

    @Test fun verifiedPrivateImageBecomesProviderDataUrlAndTamperingIsRejected() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        val source = object : AttachmentContentSource {
            override fun declaredMimeType(contentRef: String) = "image/png"
            override fun byteLength(contentRef: String) = png.size.toLong()
            override fun durationMs(contentRef: String) = null
            override fun open(contentRef: String): InputStream = ByteArrayInputStream(png)
        }
        val stager = AppPrivateAttachmentStager.forProduction(
            context,
            source,
            InputLimits(1, 16, 16, 1_000, setOf("image/png")),
        )
        stager.rootForTest.deleteRecursively()
        stager.rootForTest.mkdirs()
        val expiry = System.currentTimeMillis() + 60_000
        val ref = stager.stage("vision-session", "content://fixture", expiry)
        val attachment = ModelAttachment(
            ref.attachmentId, ref.kind.name, ref.mimeType, ref.byteLength,
            ref.sha256Digest, ref.contentRef, ref.expiresAtEpochMs, OutboundSensitivity.SENSITIVE,
            OutboundPurpose.USER_SELECTED_ATTACHMENT, OutboundProvenance("test_attachment", ref.attachmentId),
        )
        val resolver = AppPrivateProviderAttachmentResolver(context)
        assertTrue(
            resolver.imageDataUrls(
                attachment,
                System.currentTimeMillis(),
            ).single().startsWith("data:image/png;base64,"),
        )
        stager.resolveForTest(ref.contentRef).appendBytes(byteArrayOf(1))
        assertTrue(runCatching { resolver.imageDataUrls(attachment, System.currentTimeMillis()) }.isFailure)
        stager.rootForTest.deleteRecursively()
        Unit
    }

    @Test fun productionRootIsCanonicalCacheChildAndStartupSweepDeletesExpired() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        val source = object : AttachmentContentSource {
            override fun declaredMimeType(contentRef: String) = "image/png"
            override fun byteLength(contentRef: String) = png.size.toLong()
            override fun durationMs(contentRef: String) = null
            override fun open(contentRef: String): InputStream = ByteArrayInputStream(png)
        }
        val stager = AppPrivateAttachmentStager.forProduction(
            context,
            source,
            InputLimits(1, 8, 8, 1_000, setOf("image/png")),
        )
        stager.rootForTest.deleteRecursively()
        stager.rootForTest.mkdirs()
        assertTrue(stager.rootForTest.canonicalFile.toPath().startsWith(context.cacheDir.canonicalFile.toPath()))
        val ref = stager.stage("session", "content://fixture", System.currentTimeMillis() + 50)
        val file = stager.resolveForTest(ref.contentRef)
        val crashResidual = java.io.File(stager.rootForTest, "unparseable-crash.part").apply { writeText("partial") }
        assertTrue(file.exists())
        assertTrue(crashResidual.exists())
        delay(80)
        AttachmentStagingStartup(stager).runOnce()
        assertFalse(file.exists())
        assertFalse(crashResidual.exists())
    }
}
