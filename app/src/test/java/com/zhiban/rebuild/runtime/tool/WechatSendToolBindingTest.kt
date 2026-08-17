package com.zhiban.rebuild.runtime.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WechatSendToolBindingTest {
    @Test fun digestIsDeterministicAndLengthPrefixed() {
        val a = WechatSendToolBinding.wechatSendDigest("张三", "周五见")
        val b = WechatSendToolBinding.wechatSendDigest("张三", "周五见")
        assertEquals(a, b)
        assertTrue(a.matches(Regex("[0-9a-f]{64}")))
    }

    @Test fun digestChangesWithRecipientOrMessage() {
        val base = WechatSendToolBinding.wechatSendDigest("张三", "周五见")
        assertNotEquals(base, WechatSendToolBinding.wechatSendDigest("李四", "周五见"))
        assertNotEquals(base, WechatSendToolBinding.wechatSendDigest("张三", "周六见"))
    }

    @Test fun digestLengthPrefixPreventsFieldBoundaryCollisions() {
        // Without the length prefix, ("ab","c") and ("a","bc") would join to the same string.
        val x = WechatSendToolBinding.wechatSendDigest("ab", "c")
        val y = WechatSendToolBinding.wechatSendDigest("a", "bc")
        assertNotEquals(x, y)
    }

    @Test fun stableClientIdIsDeterministicFixedLengthHex() {
        val id1 = WechatSendToolBinding.stableClientId("idem-key-1")
        assertEquals(id1, WechatSendToolBinding.stableClientId("idem-key-1"))
        assertEquals(16, id1.length)
        assertTrue(id1.matches(Regex("[0-9a-f]{16}")))
        // Retries for the same logical message reuse the id (server dedups); different keys differ.
        assertNotEquals(id1, WechatSendToolBinding.stableClientId("idem-key-2"))
    }
}
