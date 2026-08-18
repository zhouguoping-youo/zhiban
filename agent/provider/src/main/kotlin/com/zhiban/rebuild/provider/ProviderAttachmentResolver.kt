package com.zhiban.rebuild.provider

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

interface ProviderAttachmentResolver {
    fun imageDataUrls(attachment: ModelAttachment, nowEpochMs: Long): List<String>
}

object RejectingProviderAttachmentResolver : ProviderAttachmentResolver {
    override fun imageDataUrls(attachment: ModelAttachment, nowEpochMs: Long): List<String> = error("ATTACHMENT_RESOLVER_UNAVAILABLE")
}

/** Resolves only files created by AppPrivateAttachmentStager; arbitrary paths and URIs are rejected. */
class AppPrivateProviderAttachmentResolver(context: Context) : ProviderAttachmentResolver {
    private val root = File(context.applicationContext.cacheDir.canonicalFile, "zhiban-runtime-input").canonicalFile

    override fun imageDataUrls(attachment: ModelAttachment, nowEpochMs: Long): List<String> {
        require(
            (attachment.kind.equals("IMAGE", true) && attachment.mimeType in ALLOWED_IMAGES) ||
                attachment.mimeType == "application/pdf",
        ) { "ATTACHMENT_MODALITY_UNSUPPORTED" }
        require(attachment.expiresAtEpochMs > nowEpochMs) { "ATTACHMENT_EXPIRED" }
        require(attachment.contentRef.startsWith("cache://")) { "ATTACHMENT_REF_INVALID" }
        val name = attachment.contentRef.removePrefix("cache://")
        require(FILE_PATTERN.matches(name)) { "ATTACHMENT_REF_INVALID" }
        val file = File(root, name).canonicalFile
        require(file.toPath().startsWith(root.toPath()) && file.isFile) { "ATTACHMENT_MISSING" }
        require(file.length() == attachment.byteLength && file.length() in 1..MAX_IMAGE_BYTES) {
            "ATTACHMENT_LENGTH_MISMATCH"
        }
        val bytes = file.readBytes()
        try {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            require(digest == attachment.sha256Digest) { "ATTACHMENT_DIGEST_MISMATCH" }
            if (attachment.mimeType in ALLOWED_IMAGES) {
                return listOf("data:${attachment.mimeType};base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}")
            }
        } finally {
            bytes.fill(0)
        }
        return renderPdf(file)
    }

    private fun renderPdf(file: File): List<String> {
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return descriptor.use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                require(renderer.pageCount in 1..MAX_PDF_PAGES_TOTAL) { "PDF_PAGE_COUNT_INVALID" }
                (0 until minOf(renderer.pageCount, MAX_RENDERED_PAGES)).map { index ->
                    renderer.openPage(index).use { page ->
                        val scale =
                            minOf(MAX_PAGE_WIDTH.toFloat() / page.width, MAX_PAGE_HEIGHT.toFloat() / page.height, 2f)
                        val width = (page.width * scale).toInt().coerceAtLeast(1)
                        val height = (page.height * scale).toInt().coerceAtLeast(1)
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        try {
                            bitmap.eraseColor(android.graphics.Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            val output = ByteArrayOutputStream()
                            bitmap.compress(Bitmap.CompressFormat.PNG, PNG_COMPRESSION_QUALITY, output)
                            val rendered = output.toByteArray()
                            try {
                                "data:image/png;base64,${Base64.encodeToString(rendered, Base64.NO_WRAP)}"
                            } finally {
                                rendered.fill(0)
                                output.reset()
                            }
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }
            }
        }
    }

    private companion object {
        const val MAX_IMAGE_BYTES = 10L * 1024 * 1024
        const val MAX_RENDERED_PAGES = 3
        const val MAX_PDF_PAGES_TOTAL = 500
        const val MAX_PAGE_WIDTH = 1600
        const val MAX_PAGE_HEIGHT = 2200
        val ALLOWED_IMAGES = setOf("image/png", "image/jpeg", "image/gif", "image/webp")
        val FILE_PATTERN = Regex("zbi_(\\d+)_([0-9a-f]{32})_([0-9a-f]{32})\\.bin")
    }
}

private const val PNG_COMPRESSION_QUALITY = 90
