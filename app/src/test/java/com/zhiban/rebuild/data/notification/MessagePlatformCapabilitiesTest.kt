package com.zhiban.rebuild.data.notification

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagePlatformCapabilitiesTest {
    @Test
    fun `wechat qq and wework enable verified message intelligence`() {
        listOf("WECHAT", "QQ", "WEWORK").forEach { platform ->
            val capability = MessagePlatformCapabilities.forPlatform(platform)
            assertTrue(capability.replySuggestions)
            assertTrue(capability.profileExtraction)
            assertTrue(capability.proactiveWakeup)
            assertTrue(capability.relationshipInference)
        }
    }

    @Test
    fun `only wechat tracks proactive completion replies`() {
        assertTrue(MessagePlatformCapabilities.forPlatform("WECHAT").completionReplyTracking)
        assertFalse(MessagePlatformCapabilities.forPlatform("QQ").completionReplyTracking)
        assertFalse(MessagePlatformCapabilities.forPlatform("WEWORK").completionReplyTracking)
    }

    @Test
    fun `unverified platforms remain collection only`() {
        listOf("DINGTALK", "FEISHU", "LARK", "TIM", "SMS").forEach { platform ->
            val capability = MessagePlatformCapabilities.forPlatform(platform)
            assertFalse(capability.replySuggestions)
            assertFalse(capability.profileExtraction)
            assertFalse(capability.proactiveWakeup)
            assertFalse(capability.relationshipInference)
            assertFalse(capability.completionReplyTracking)
        }
    }
}
