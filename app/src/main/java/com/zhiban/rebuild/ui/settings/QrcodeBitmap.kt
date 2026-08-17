package com.zhiban.rebuild.ui.settings

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Renders [content] as a QR code bitmap on-device. Returns null for blank content or an encode
 * failure (the caller shows a retry hint).
 *
 * The iLink bind response's `qrcode_img_content` is the *payload* the QR must encode — a
 * `liteapp.weixin.qq.com` landing URL carrying the bind token — not a displayable image: fetching
 * that URL returns an HTML page, so an image loader renders a blank box. Encoding the payload
 * locally is the correct semantic. WeChat scans the QR, reads the URL, extracts the embedded token
 * and drives the bind confirmation, which our `get_qrcode_status` poll then observes.
 */
@Composable
internal fun rememberQrcodeBitmap(content: String?, sizePx: Int): ImageBitmap? = remember(content, sizePx) {
    content?.takeIf { it.isNotBlank() }?.let { runCatching { qrcodeBitmap(it, sizePx) }.getOrNull() }
}

private fun qrcodeBitmap(content: String, sizePx: Int): ImageBitmap {
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx)
    val width = matrix.width
    val height = matrix.height
    val pixels = IntArray(width * height)
    for (y in 0 until height) {
        val offset = y * width
        for (x in 0 until width) {
            pixels[offset + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
        }
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()
}
