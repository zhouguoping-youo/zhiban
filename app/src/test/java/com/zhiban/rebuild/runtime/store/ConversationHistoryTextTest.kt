package com.zhiban.rebuild.runtime.store

import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationHistoryTextTest {
    @Test
    fun `extracts text from runtime input envelope`() {
        val raw = """{"schemaVersion":1,"text":"你好知伴","mode":"Work","model":"step-3.5-flash"}"""
        assertEquals("你好知伴", userFacingConversationText(raw))
    }

    @Test
    fun `repairs escaped and control characters in legacy preview`() {
        val raw = """{"schemaVersion":1,"text":"\f hgg","mode":"Work"}"""
        assertEquals("hgg", userFacingConversationText(raw))
    }

    @Test
    fun `supports legacy double encoded envelope`() {
        val raw = """"{\"schemaVersion\":1,\"text\":\"测试历史\",\"mode\":\"Work\"}""""
        assertEquals("测试历史", userFacingConversationText(raw))
    }

    @Test
    fun `keeps normal user text and normalizes whitespace`() {
        assertEquals("普通 对话", userFacingConversationText("普通\n\t对话"))
    }
}
