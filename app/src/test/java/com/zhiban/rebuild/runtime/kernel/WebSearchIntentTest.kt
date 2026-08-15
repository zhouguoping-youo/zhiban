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
}
