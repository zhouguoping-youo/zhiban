package com.zhiban.rebuild.data.ilink.network

import java.security.SecureRandom

/**
 * Generates the `X-WECHAT-UIN` header sent on every authenticated iLink request.
 *
 * The algorithm (per the iLink Bot protocol references) is: draw 4 random bytes, interpret them as
 * an unsigned 32-bit integer, render that integer as a **decimal string**, then base64-encode the
 * decimal string's UTF-8 bytes. The documented trap is to base64 the raw 4 bytes instead of the
 * decimal text — that yields a value the server rejects. A fresh value is generated per request and
 * never reused.
 *
 * `java.util.Base64` is used (not `android.util.Base64`) so the generator stays JVM-unit-testable;
 * the basic RFC 4648 encoder matches `Base64.NO_WRAP` semantics (padded, single line).
 */
class WechatUinGenerator(private val random: SecureRandom = SecureRandom()) {

    fun generate(): String {
        val bytes = ByteArray(UINT_BYTE_COUNT)
        random.nextBytes(bytes)
        val unsigned = bytes.fold(0L) { acc, byte -> (acc shl 8) or (byte.toLong() and 0xFF) }
        val decimalString = unsigned.toString()
        return java.util.Base64.getEncoder().encodeToString(decimalString.toByteArray(Charsets.UTF_8))
    }

    private companion object {
        const val UINT_BYTE_COUNT = 4
    }
}
