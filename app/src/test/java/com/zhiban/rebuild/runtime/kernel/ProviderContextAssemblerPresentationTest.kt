package com.zhiban.rebuild.runtime.kernel

import com.zhiban.rebuild.runtime.context.ContextRetrievalResult
import com.zhiban.rebuild.runtime.context.IntentLabel
import com.zhiban.rebuild.runtime.context.QueryContext
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderContextAssemblerPresentationTest {
    @Test
    fun systemPolicyHidesImplementationDetailsUnlessTheUserAsks() {
        val result = ProviderContextAssembler(clock = { 0L }, personalization = { null }).assembleMessages(
            input = DecodedInput(text = "今天有什么安排", mode = "Work"),
            queryContext = QueryContext(IntentLabel.CALENDAR_QUERY, 1.0, emptyList(), null, emptyList()),
            retrieval = ContextRetrievalResult(emptyList(), 0, emptyList(), 0),
            memories = emptyList(),
            sessionSummary = null,
            recentConversation = emptyList(),
            feedback = emptyList(),
            activatedSkills = emptyList(),
            maxContextTokens = 4_096,
        )

        val policy = result.messages.first { it.role == "system" }.content
        assertTrue(policy.contains("不要展示工具名、内部状态、检索条数或实现说明"))
    }
}
