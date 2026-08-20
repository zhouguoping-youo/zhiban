package com.zhiban.rebuild.runtime.kernel

import com.zhiban.rebuild.runtime.tool.CommunicationMessageToolBinding
import com.zhiban.rebuild.runtime.tool.SchedulePlanValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ProviderExecutionDomainLogic 纯决策函数的直接测试(审计测试盲区1:flows 拆分件无直接测试,
 * 但其纯决策逻辑集中在本文件,先补满这里的 JVM 覆盖)。
 */
class ProviderExecutionDomainLogicTest {
    @Test
    fun `decode input defaults to user authored and recognizes automatic input`() {
        assertEquals(InputOrigin.USER_AUTHORED, decodeInput("普通用户输入").origin)
        assertEquals(
            InputOrigin.USER_AUTHORED,
            decodeInput("""{"text":"用户输入","origin":"UNKNOWN"}""").origin,
        )
        assertEquals(
            InputOrigin.AUTO_RETRIEVED,
            decodeInput("""{"text":"后台消息","origin":"AUTO_RETRIEVED"}""").origin,
        )
    }

    @Test fun `assistant text streams unless a schedule plan is being forced`() {
        assertTrue(shouldStreamAssistantText(null))
        assertTrue(shouldStreamAssistantText("contact.search"))
        assertFalse(shouldStreamAssistantText(SchedulePlanValidator.TOOL_NAME))
    }

    @Test fun `explicit calendar durations parse chinese and english`() {
        assertEquals(30, explicitCalendarDurationMinutes("会议时长半小时"))
        assertEquals(60, explicitCalendarDurationMinutes("会议时长1小时"))
        assertEquals(90, explicitCalendarDurationMinutes("会议时长1.5小时"))
        assertEquals(45, explicitCalendarDurationMinutes("会议时长45分钟"))
        assertEquals(60, explicitCalendarDurationMinutes("for 1 hour"))
        assertEquals(15, explicitCalendarDurationMinutes("duration 15 minutes"))
        assertNull(explicitCalendarDurationMinutes("明天上午开会"))
        assertNull(explicitCalendarDurationMinutes("会议时长0分钟")) // 0 不在 1..1440
    }

    @Test fun `schedule title sanitization falls back sensibly`() {
        assertEquals("嗯", sanitizeScheduleTitleFromText("嗯", "")) // 非空输入回退到原文
        assertEquals("新日程", sanitizeScheduleTitleFromText("", "日程提醒"))
    }

    @Test fun `feedback context message counts by kind`() {
        val message = feedbackContextMessage(listOf("POSITIVE", "POSITIVE", "NEGATIVE"))
        assertTrue(message.contains("好评=2"))
        assertTrue(message.contains("需改进=1"))
    }

    @Test fun `remaining observation requirements gate on explicit asks`() {
        assertEquals("", remainingObservationRequirements("今天天气如何", emptySet()))
        val contactAsk = remainingObservationRequirements("我有多少位联系人", emptySet())
        assertTrue(contactAsk.contains("contact.maintenance.list"))
        assertEquals("", remainingObservationRequirements("我有多少位联系人", setOf("contact.maintenance.list")))
        val calendarAsk = remainingObservationRequirements("今天有什么安排", emptySet())
        assertTrue(calendarAsk.contains("calendar.schedule.search"))
        assertEquals("", remainingObservationRequirements("今天有什么安排", setOf("calendar.schedule.search")))
    }

    @Test fun `forced tool decisions match explicit intents`() {
        assertTrue(shouldForceWebSearch("帮我联网搜索一下最新行情"))
        assertFalse(shouldForceWebSearch("明天天气怎么样"))

        assertTrue(shouldForceMemoryUpsert("记住我喜欢喝美式"))
        assertFalse(shouldForceMemoryUpsert("记住明天给我订票")) // 一次性任务模式
        assertFalse(shouldForceMemoryUpsert("明天天气怎么样"))
    }

    @Test fun `deterministic observation completion covers calendar and communication`() {
        assertTrue(shouldCompleteObservationDeterministically("calendar.conflicts", com.zhiban.rebuild.runtime.context.IntentLabel.GENERAL_CHAT))
        assertTrue(
            shouldCompleteObservationDeterministically(CommunicationMessageToolBinding.TOOL_NAME, com.zhiban.rebuild.runtime.context.IntentLabel.GENERAL_CHAT),
        )
        assertFalse(shouldCompleteObservationDeterministically("contact.search", com.zhiban.rebuild.runtime.context.IntentLabel.GENERAL_CHAT))
        assertFalse(shouldCompleteObservationDeterministically(SchedulePlanValidator.TOOL_NAME, com.zhiban.rebuild.runtime.context.IntentLabel.GENERAL_CHAT))
    }

    @Test fun `deterministic tool summaries cover calendar and contacts`() {
        assertEquals("这个时间范围内没有日程安排。", deterministicToolSummary("calendar.schedule.search", """{"count":0}"""))
        assertEquals("已查到 3 条日程。", deterministicToolSummary("calendar.schedule.search", """{"count":3}"""))
        assertEquals("没有找到匹配的联系人。", deterministicToolSummary("contact.search", """{"count":0}"""))
        assertEquals("联系人总数：5 人。", deterministicToolSummary("contact.maintenance.list", """{"totalContactCount":5}"""))
    }
}
