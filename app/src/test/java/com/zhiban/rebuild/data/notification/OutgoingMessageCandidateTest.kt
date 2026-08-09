package com.zhiban.rebuild.data.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutgoingMessageCandidateTest {
    @Test
    fun createsOutgoingCandidateForSupportedApp() {
        val value = outgoingAccessibilityCandidate(
            packageName = "com.tencent.mm",
            appLabel = "微信",
            conversationTitle = "周国平",
            body = "明天下午三点开会",
            postedAtEpochMs = 1_800_000L,
        )

        requireNotNull(value)
        assertEquals("WECHAT", value.platform)
        assertEquals("OUTGOING", value.direction)
        assertEquals("ACCESSIBILITY", value.sourceType)
        assertEquals("周国平", value.conversationTitle)
        assertTrue(value.insightJson.orEmpty().contains("schedule"))
    }

    @Test
    fun rejectsUnsupportedPackageAndOneTimeCodes() {
        assertNull(
            outgoingAccessibilityCandidate(
                "com.example.unknown",
                "未知",
                "某人",
                "你好",
                1_800_000L,
            ),
        )
        assertNull(
            outgoingAccessibilityCandidate(
                "com.tencent.mm",
                "微信",
                "某人",
                "登录验证码 123456",
                1_800_000L,
            ),
        )
        assertNull(
            outgoingAccessibilityCandidate(
                "com.tencent.mm",
                "微信",
                "某人",
                "交易密码 482913",
                1_800_000L,
            ),
        )
        assertNull(
            outgoingAccessibilityCandidate(
                "com.tencent.mm",
                "微信",
                "某人",
                "密钥 sk_test_1234567890",
                1_800_000L,
            ),
        )
    }

    @Test
    fun stableWithinDedupeWindowButChangesAcrossWindow() {
        val first = outgoingAccessibilityCandidate(
            "com.tencent.mm",
            "微信",
            "某人",
            "重复内容",
            1_800_000L,
        )
        val duplicate = outgoingAccessibilityCandidate(
            "com.tencent.mm",
            "微信",
            "某人",
            "重复内容",
            1_800_100L,
        )
        val later = outgoingAccessibilityCandidate(
            "com.tencent.mm",
            "微信",
            "某人",
            "重复内容",
            2_100_000L,
        )

        assertEquals(first?.sourceKey, duplicate?.sourceKey)
        assertTrue(first?.sourceKey != later?.sourceKey)
    }
}
