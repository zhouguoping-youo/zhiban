package com.zhiban.rebuild.runtime.kernel

import com.zhiban.rebuild.data.store.RuntimeConversationTurnEntity
import com.zhiban.rebuild.provider.CapabilitySnapshot
import com.zhiban.rebuild.provider.DefaultOutboundDataPolicy
import com.zhiban.rebuild.provider.ModelRequest
import com.zhiban.rebuild.provider.OutboundChannel
import com.zhiban.rebuild.provider.OutboundPolicySettings
import com.zhiban.rebuild.provider.OutboundPurpose
import com.zhiban.rebuild.provider.ProviderProfile
import com.zhiban.rebuild.runtime.context.ContextRetrievalResult
import com.zhiban.rebuild.runtime.context.IntentLabel
import com.zhiban.rebuild.runtime.context.QueryContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderContextAssemblerPresentationTest {
    @Test
    fun systemPolicyHidesImplementationDetailsUnlessTheUserAsks() {
        val result = ProviderContextAssembler(clock = { 0L }, personalization = { null }).assembleMessages(
            input = DecodedInput(text = "今天有什么安排", mode = "Work"),
            query = QueryAssemblyContext(
                QueryContext(IntentLabel.CALENDAR_QUERY, 1.0, emptyList(), null, emptyList()),
                ContextRetrievalResult(emptyList(), 0, emptyList(), 0),
            ),
            session = SessionAssemblyContext(
                memories = emptyList(),
                summary = null,
                recentTurns = emptyList(),
                feedback = emptyList(),
            ),
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
            query = QueryAssemblyContext(
                QueryContext(IntentLabel.CALENDAR_CREATE, 1.0, emptyList(), null, emptyList()),
                ContextRetrievalResult(emptyList(), 0, emptyList(), 0),
            ),
            session = SessionAssemblyContext(
                memories = listOf("历史上下文".repeat(300)),
                summary = "会话摘要".repeat(300),
                recentTurns = emptyList(),
                feedback = emptyList(),
            ),
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
        // A user-authored history turn is a deliberate send (not redacted); an assistant turn is not.
        assertEquals(OutboundPurpose.USER_AUTHORED, conversation[0].purpose)
        assertEquals(OutboundPurpose.AUTO_RETRIEVED, conversation[1].purpose)
        assertEquals("user", result.messages.last().role)
        assertEquals("接着上面说", result.messages.last().content)
        assertEquals(OutboundPurpose.USER_AUTHORED, result.messages.last().purpose)
    }

    @Test
    fun userAuthoredHistoryPassesIntactWhileAssistantEchoFollowsPhoneNumberSwitch() {
        val assembled = assembleWithHistory(
            listOf(
                turn("turn-private", "user", "我的电话是13800000000", 1),
                turn("turn-answer", "assistant", "已存联系人号码13800000000", 2),
            ),
        )

        // 默认:号码明文交给大模型(优先可用性),助手回声里的号码不再打码。
        val governedByDefault = DefaultOutboundDataPolicy().enforce(requestFrom(assembled)).request
        val recalledUserByDefault = governedByDefault.messages.first { it.provenance.sourceId == "turn-private" }
        assertEquals("user", recalledUserByDefault.role)
        assertEquals(OutboundPurpose.USER_AUTHORED, recalledUserByDefault.purpose)
        assertEquals("我的电话是13800000000", recalledUserByDefault.content)

        val recalledAssistantByDefault = governedByDefault.messages.first { it.provenance.sourceId == "turn-answer" }
        assertEquals("assistant", recalledAssistantByDefault.role)
        assertEquals(OutboundPurpose.AUTO_RETRIEVED, recalledAssistantByDefault.purpose)
        assertEquals("已存联系人号码13800000000", recalledAssistantByDefault.content)

        // 关闭号码明文后,助手回声(源于已存数据)重新打码;用户亲手输入的内容始终原样送达。
        val governedMasked = DefaultOutboundDataPolicy {
            OutboundPolicySettings(allowUnmaskedPhoneNumbers = false)
        }.enforce(requestFrom(assembled)).request
        val recalledUserMasked = governedMasked.messages.first { it.provenance.sourceId == "turn-private" }
        assertEquals("我的电话是13800000000", recalledUserMasked.content)
        val recalledAssistantMasked = governedMasked.messages.first { it.provenance.sourceId == "turn-answer" }
        assertEquals("已存联系人号码138****0000", recalledAssistantMasked.content)

        assertEquals("接着上面说", governedByDefault.messages.last().content)
    }

    @Test
    fun automaticallyCapturedInputIsMarkedAndRedactedBeforeProviderExport() {
        val assembled = ProviderContextAssembler(clock = { 0L }, personalization = { null }).assembleMessages(
            input = DecodedInput(
                text = "联系人回复：电话13800000000，邮箱auto@example.com",
                mode = "Work",
                origin = InputOrigin.AUTO_RETRIEVED,
            ),
            query = QueryAssemblyContext(
                QueryContext(IntentLabel.GENERAL_CHAT, 0.0, emptyList(), null, emptyList()),
                ContextRetrievalResult(emptyList(), 0, emptyList(), 0),
            ),
            session = SessionAssemblyContext(emptyList(), null, emptyList(), emptyList()),
            activatedSkills = emptyList(),
            maxContextTokens = 4_096,
        )

        val automaticInput = assembled.messages.last()
        assertEquals("user", automaticInput.role)
        assertEquals(OutboundPurpose.AUTO_RETRIEVED, automaticInput.purpose)
        assertEquals("automatic_input", automaticInput.provenance.sourceType)

        val governed = DefaultOutboundDataPolicy {
            OutboundPolicySettings(allowUnmaskedPhoneNumbers = false)
        }.enforce(requestFrom(assembled)).request.messages.last()
        assertTrue(governed.content.contains("138****0000"))
        assertTrue(!governed.content.contains("auto@example.com"))
    }

    private fun requestFrom(assembled: AssembledModelContext) = ModelRequest(
        requestId = "request-history",
        channel = OutboundChannel.LLM_INFERENCE,
        profile = ProviderProfile("stepfun", "primary", "model", "credential", 1),
        messages = assembled.messages,
        capability = CapabilitySnapshot("profile", emptySet(), emptySet(), 4_096, 2_048, 0, Long.MAX_VALUE),
        maxTokens = 2_048,
    )

    private fun assembleWithHistory(history: List<RuntimeConversationTurnEntity>) =
        ProviderContextAssembler(clock = { 0L }, personalization = { null }).assembleMessages(
            input = DecodedInput(text = "接着上面说", mode = "Chat"),
            query = QueryAssemblyContext(
                QueryContext(IntentLabel.GENERAL_CHAT, 0.0, emptyList(), null, emptyList()),
                ContextRetrievalResult(emptyList(), 0, emptyList(), 0),
            ),
            session = SessionAssemblyContext(
                memories = emptyList(),
                summary = null,
                recentTurns = history,
                feedback = emptyList(),
            ),
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
