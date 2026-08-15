package com.zhiban.rebuild.runtime.kernel

import com.zhiban.rebuild.runtime.context.ContextRetrievalResult
import com.zhiban.rebuild.runtime.context.IntentLabel
import com.zhiban.rebuild.runtime.context.QueryContext
import com.zhiban.rebuild.runtime.provider.CapabilitySnapshot
import com.zhiban.rebuild.runtime.provider.DefaultOutboundDataPolicy
import com.zhiban.rebuild.runtime.provider.ModelRequest
import com.zhiban.rebuild.runtime.provider.OutboundChannel
import com.zhiban.rebuild.runtime.provider.OutboundPurpose
import com.zhiban.rebuild.runtime.provider.ProviderProfile
import com.zhiban.rebuild.runtime.store.RuntimeConversationTurnEntity
import org.junit.Assert.assertEquals
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
        assertTrue(policy.contains("仅同事和上下级依赖用户当前公司全称"))
        assertTrue(policy.contains("不能在非工作关系下机械追问当前公司"))
        assertTrue(policy.contains("客户与合作方依据真实项目、合同、订单或业务沟通"))
    }

    @Test
    fun currentUserInputIsNeverOmittedWhenSessionContextFillsBudget() {
        val currentInput = "请根据刚才的信息安排明晚八点的日程".repeat(30)
        val result = ProviderContextAssembler(clock = { 0L }, personalization = { null }).assembleMessages(
            input = DecodedInput(text = currentInput, mode = "Work"),
            queryContext = QueryContext(IntentLabel.CALENDAR_CREATE, 1.0, emptyList(), null, emptyList()),
            retrieval = ContextRetrievalResult(emptyList(), 0, emptyList(), 0),
            memories = listOf("历史上下文".repeat(300)),
            sessionSummary = "会话摘要".repeat(300),
            recentConversation = emptyList(),
            feedback = emptyList(),
            activatedSkills = emptyList(),
            maxContextTokens = 2_000,
        )

        val userMessages = result.messages.filter { it.role == "user" }
        assertEquals(1, userMessages.size)
        assertEquals(currentInput, userMessages.single().content)
    }

    @Test
    fun recentConversationRetainsAlternatingRolesBeforeCurrentInput() {
        val result = assembleWithHistory(
            listOf(
                turn("turn-1", "user", "张三是做什么的？", 1),
                turn("turn-2", "assistant", "张三负责数据库产品。", 2),
            ),
        )

        val conversation = result.messages.filter { it.provenance.sourceType == "session_memory" }
        assertEquals(listOf("user", "assistant"), conversation.map { it.role })
        assertEquals(listOf("张三是做什么的？", "张三负责数据库产品。"), conversation.map { it.content })
        assertEquals(OutboundPurpose.AUTO_RETRIEVED, conversation.first().purpose)
        assertEquals("user", result.messages.last().role)
        assertEquals("接着上面说", result.messages.last().content)
        assertEquals(OutboundPurpose.USER_AUTHORED, result.messages.last().purpose)
    }

    @Test
    fun recalledConversationStillPassesOutboundRedaction() {
        val assembled = assembleWithHistory(
            listOf(
                turn("turn-private", "user", "我的电话是13800000000", 1),
                turn("turn-answer", "assistant", "我记住了你的电话。", 2),
            ),
        )
        val governed = DefaultOutboundDataPolicy().enforce(
            ModelRequest(
                requestId = "request-history",
                channel = OutboundChannel.LLM_INFERENCE,
                profile = ProviderProfile("stepfun", "primary", "model", "credential", 1),
                messages = assembled.messages,
                capability = CapabilitySnapshot("profile", emptySet(), emptySet(), 4_096, 2_048, 0, Long.MAX_VALUE),
                maxTokens = 2_048,
            ),
        ).request

        val recalledUser = governed.messages.first { it.provenance.sourceId == "turn-private" }
        assertEquals("user", recalledUser.role)
        assertEquals(OutboundPurpose.AUTO_RETRIEVED, recalledUser.purpose)
        assertEquals("我的电话是138****0000", recalledUser.content)
        assertEquals("接着上面说", governed.messages.last().content)
    }

    private fun assembleWithHistory(history: List<RuntimeConversationTurnEntity>) =
        ProviderContextAssembler(clock = { 0L }, personalization = { null }).assembleMessages(
            input = DecodedInput(text = "接着上面说", mode = "Chat"),
            queryContext = QueryContext(IntentLabel.GENERAL_CHAT, 0.0, emptyList(), null, emptyList()),
            retrieval = ContextRetrievalResult(emptyList(), 0, emptyList(), 0),
            memories = emptyList(),
            sessionSummary = null,
            recentConversation = history,
            feedback = emptyList(),
            activatedSkills = emptyList(),
            maxContextTokens = 4_096,
        )

    private fun turn(turnId: String, role: String, content: String, createdAt: Long) = RuntimeConversationTurnEntity(
        turnId = turnId,
        sessionId = "session",
        runId = "run-$turnId",
        role = role,
        content = content,
        contentDigest = "digest-$turnId",
        tokenEstimate = 8,
        createdAtEpochMs = createdAt,
    )
}
