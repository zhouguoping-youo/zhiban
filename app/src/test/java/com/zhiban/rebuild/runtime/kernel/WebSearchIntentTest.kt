package com.zhiban.rebuild.runtime.kernel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchIntentTest {
    @Test fun explicitFreshInformationRequestsRequireWebSearch() {
        assertTrue(shouldForceWebSearch("联网搜索今天北京天气"))
        assertTrue(shouldForceWebSearch("Search the web for the current weather in Beijing"))
        assertTrue(shouldForceWebSearch("帮我查一下最新的公开行业新闻"))
    }

    @Test fun localContactAndCalendarSearchesStayOnDevice() {
        assertFalse(shouldForceWebSearch("搜索联系人张三"))
        assertFalse(shouldForceWebSearch("查一下我今天的日程"))
        assertFalse(shouldForceWebSearch("搜索我的长期记忆"))
    }

    @Test fun stablePreferencesUseAutomaticMemoryButOneTimeTasksDoNot() {
        assertTrue(shouldForceMemoryUpsert("记住我喜欢先看结论"))
        assertTrue(shouldForceMemoryUpsert("Remember that I prefer concise answers"))
        assertFalse(shouldForceMemoryUpsert("提醒我明天交报告"))
        assertFalse(shouldForceMemoryUpsert("Remember tomorrow's meeting"))
        assertFalse(shouldForceMemoryUpsert("安排复盘会并记住我喜欢短会"))
    }

    @Test fun calendarThenWebThenMemoryDefineForcedToolPrecedence() {
        val available = setOf(
            "calendar.schedule.create",
            "web.search",
            "memory.upsert",
            "contact.maintenance.list",
            "calendar.schedule.search",
        )
        fun select(input: String, calendar: Boolean = false) = selectForcedCanonicalTool(
            ForcedToolSelection(true, calendar, input, available, null) { true },
        )

        assertTrue(select("安排明晚会议", calendar = true) == "calendar.schedule.create")
        assertTrue(select("Search the web for current weather") == "web.search")
        assertTrue(select("Remember that I prefer concise answers") == "memory.upsert")
    }
}
