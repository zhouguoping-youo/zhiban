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
        assertTrue(policy.contains("totalContactCount 才是全库人数"))
        assertTrue(policy.contains("不得要求用户逐个填写所有联系人的入职时间"))
        assertTrue(policy.contains("每轮严格只问一个问题"))
        assertTrue(policy.contains("不能因为 contactCardLinked 为 false 就再次询问用户是哪张名片"))
        assertTrue(policy.contains("contactId 固定为 user:self"))
        assertTrue(policy.contains("确认卡出现前不得说已确认"))
        assertTrue(policy.contains("用户暂不完善就保留待发现"))
    }
}
