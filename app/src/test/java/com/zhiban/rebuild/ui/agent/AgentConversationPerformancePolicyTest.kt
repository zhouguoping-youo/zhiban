package com.zhiban.rebuild.ui.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentConversationPerformancePolicyTest {
    @Test
    fun streamingUpdatesOnlyStickWhenTheReaderIsAtTheLatestRows() {
        assertTrue(shouldAutoScrollToLatest(totalItems = 20, lastVisibleIndex = 19))
        assertTrue(shouldAutoScrollToLatest(totalItems = 20, lastVisibleIndex = 18))
        assertFalse(shouldAutoScrollToLatest(totalItems = 20, lastVisibleIndex = 10))
    }
}
