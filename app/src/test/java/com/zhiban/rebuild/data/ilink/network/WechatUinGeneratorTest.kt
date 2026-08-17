package com.zhiban.rebuild.data.ilink.network

import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WechatUinGeneratorTest {
    @Test fun maxUint32EncodesDecimalTextNotRawBytes() {
        // 0xFFFFFFFF as uint32 is 4294967295; the header must be base64 of the decimal *string*.
        val generator = WechatUinGenerator(fixedBytes(0xFF.toByte()))
        assertEquals(base64Of("4294967295"), generator.generate())
    }

    @Test fun zeroEncodesDecimalZero() {
        val generator = WechatUinGenerator(fixedBytes(0x00))
        assertEquals(base64Of("0"), generator.generate())
    }

    @Test fun mixedBytesFollowBigEndianUnsignedOrder() {
        // 00 00 01 00 -> 256 as an unsigned big-endian 32-bit integer.
        val generator = WechatUinGenerator(fixedBytes(0x00, 0x00, 0x01, 0x00))
        assertEquals(base64Of("256"), generator.generate())
    }

    @Test fun generatedValueDecodesToUint32RangeDecimalString() {
        val generator = WechatUinGenerator()
        repeat(64) {
            val decoded = String(java.util.Base64.getDecoder().decode(generator.generate()), Charsets.UTF_8)
            assertTrue("decoded value must be ASCII digits, was: $decoded", decoded.all(Char::isDigit))
            val value = decoded.toLong()
            assertTrue(value in 0..4_294_967_295L)
        }
    }

    private fun fixedBytes(vararg values: Byte): SecureRandom = object : SecureRandom() {
        override fun nextBytes(bytes: ByteArray) {
            for (i in bytes.indices) bytes[i] = values[i % values.size]
        }
    }

    private fun base64Of(decimalText: String): String = java.util.Base64.getEncoder().encodeToString(decimalText.toByteArray(Charsets.UTF_8))
}
