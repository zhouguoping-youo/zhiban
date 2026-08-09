package com.zhiban.rebuild.ui.agent.projection

import com.zhiban.rebuild.runtime.spi.BudgetProjection
import com.zhiban.rebuild.runtime.spi.PendingApprovalProjection
import com.zhiban.rebuild.runtime.spi.RuntimeAction
import com.zhiban.rebuild.runtime.spi.RuntimeRunStatus
import com.zhiban.rebuild.runtime.spi.SessionProjection
import com.zhiban.rebuild.runtime.spi.SourceProjection
import com.zhiban.rebuild.ui.agent.AgentConversationStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentProjectionUiMapperTest {
    @Test
    fun `session without a run stays empty`() {
        val result = AgentProjectionUiMapper.map(SessionProjection(sessionId = "session-1"))

        assertEquals(AgentConversationStage.EMPTY, result.stage)
        assertEquals(true, result.isInputEnabled)
    }

    @Test
    fun `confirmation projection maps approval budget and sources`() {
        val result = AgentProjectionUiMapper.map(
            SessionProjection(
                sessionId = "session-1",
                runId = "run-1",
                revision = 4,
                runStatus = RuntimeRunStatus.AWAITING_CONFIRMATION,
                assistantText = "我帮你整理了这些：",
                pendingApproval = PendingApprovalProjection("proposal-1", "payload-ref-1", "创建日程"),
                budget = BudgetProjection(120, 1000),
                sources = listOf(SourceProjection("calendar", "日历")),
            ),
        )

        assertEquals(AgentConversationStage.AWAITING_CONFIRMATION, result.stage)
        assertEquals("proposal-1", result.pendingProposalId)
        assertEquals(120, result.usedTokens)
        assertEquals(listOf("日历"), result.sourceLabels)
    }

    @Test
    fun `communication approval exposes exact recipient and body`() {
        val result = AgentProjectionUiMapper.map(
            SessionProjection(
                sessionId = "session-1",
                runId = "run-1",
                lastAppliedSequence = 2,
                runStatus = RuntimeRunStatus.AWAITING_CONFIRMATION,
                pendingApproval = PendingApprovalProjection(
                    "proposal-1",
                    "payload-ref-1",
                    "打开微信发送消息",
                    platform = "WECHAT",
                    recipient = "周国平",
                    message = "明天下午三点见。",
                ),
            ),
        )

        assertEquals("周国平", result.plan?.recipient)
        assertEquals("明天下午三点见。", result.plan?.message)
        assertEquals("WECHAT", result.plan?.platform)
    }

    @Test
    fun `cancel requested remains non terminal and disables input`() {
        val result = AgentProjectionUiMapper.map(
            SessionProjection(
                sessionId = "session-1",
                runId = "run-1",
                runStatus = RuntimeRunStatus.CANCEL_REQUESTED,
                allowedActions = setOf(RuntimeAction.CANCEL, RuntimeAction.RESUME),
            ),
        )

        assertEquals(AgentConversationStage.EXECUTING, result.stage)
        assertEquals("正在取消，请稍候…", result.safeMessage)
        assertFalse(result.isInputEnabled)
        assertTrue(result.canCancel)
        assertTrue(result.canResume)
    }

    @Test
    fun `unsupported schema is read only and fail closed`() {
        val result = AgentProjectionUiMapper.map(
            SessionProjection(sessionId = "session-1", readOnly = true),
        )

        assertEquals(AgentConversationStage.FAILED_FINAL, result.stage)
        assertEquals("当前版本无法安全读取这段会话，请升级后重试。", result.safeMessage)
        assertFalse(result.isInputEnabled)
    }

    @Test
    fun `failure taxonomy maps to actionable safe Chinese messages`() {
        fun mapped(code: String, status: RuntimeRunStatus = RuntimeRunStatus.FAILED_FINAL) =
            AgentProjectionUiMapper.map(SessionProjection("s", "r", runStatus = status, safeFailureCode = code))

        val missing = mapped("PROVIDER_NOT_CONFIGURED")
        assertTrue(missing.isCredentialMissing)
        assertTrue(missing.safeMessage!!.contains("配置"))
        assertEquals("PROVIDER_NOT_CONFIGURED", missing.safeFailureCode)

        val offline = mapped("NETWORK_OFFLINE", RuntimeRunStatus.FAILED_RETRYABLE)
        assertFalse(offline.isCredentialMissing)
        assertTrue(offline.safeMessage!!.contains("本地日程"))

        val weakAttachment = mapped("WEAK_NETWORK_MULTIMODAL_DISABLED", RuntimeRunStatus.FAILED_RETRYABLE)
        assertTrue(weakAttachment.safeMessage!!.contains("图片和文件分析已暂停"))

        val invalidTool = mapped("INVALID_TOOL_CALL")
        assertTrue(invalidTool.safeMessage!!.contains("可安全执行"))

        val tls = mapped("TLS_VERIFICATION_FAILED")
        assertTrue(tls.safeMessage!!.contains("安全连接验证失败"))
        assertFalse(tls.safeMessage!!.contains("certificate", ignoreCase = true))
    }
}
