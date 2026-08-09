package com.zhiban.rebuild.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantChatRouteTest {
    @Test
    fun directAskEntryUsesConversationContext() {
        assertFalse(AssistantChat().workContext)
    }

    @Test
    fun crmEntryCanRequireWorkContext() {
        assertTrue(AssistantChat(workContext = true).workContext)
    }
}
