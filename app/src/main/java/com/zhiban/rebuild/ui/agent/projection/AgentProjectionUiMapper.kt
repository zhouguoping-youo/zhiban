package com.zhiban.rebuild.ui.agent.projection

import com.zhiban.rebuild.runtime.spi.RuntimeAction
import com.zhiban.rebuild.runtime.spi.RuntimeRunStatus
import com.zhiban.rebuild.runtime.spi.SessionProjection
import com.zhiban.rebuild.ui.agent.AgentConversationStage
import com.zhiban.rebuild.ui.agent.AgentConversationUiState
import com.zhiban.rebuild.ui.agent.AgentPlanUi
import com.zhiban.rebuild.ui.theme.DateFormats
import java.time.Instant
import java.time.ZoneId

object AgentProjectionUiMapper {
    fun map(projection: SessionProjection): AgentConversationUiState {
        if (
            projection.runId == null && projection.lastAppliedSequence == 0L && !projection.readOnly &&
            projection.safeFailureCode == null
        ) {
            return AgentConversationUiState(stage = AgentConversationStage.EMPTY, inputEnabled = true)
        }
        if (projection.readOnly) {
            return AgentConversationUiState(
                stage = AgentConversationStage.FAILED_FINAL,
                runtimeRunId = projection.runId,
                assistantMessage = projection.assistantText.ifBlank { null },
                safeMessage = "当前版本无法安全读取这段会话，请升级后重试。",
                sourceLabels = projection.sources.map { it.label },
                inputEnabled = false,
            )
        }
        val stage = projection.runStatus.toUiStage()
        val cancelling = projection.runStatus == RuntimeRunStatus.CANCEL_REQUESTED
        return AgentConversationUiState(
            stage = stage,
            runtimeRunId = projection.runId,
            assistantMessage = projection.assistantText.trim().ifBlank { null },
            safeMessage = when {
                cancelling -> "正在取消，请稍候…"
                projection.safeFailureCode == "PROVIDER_NOT_CONFIGURED" -> "尚未配置大模型服务，请先完成连接设置。"
                projection.safeFailureCode == "AUTHENTICATION_FAILED" -> "大模型连接验证失败，请检查设置后重试。"
                projection.safeFailureCode == "TLS_VERIFICATION_FAILED" -> "安全连接验证失败，请检查网络环境或更新知伴后重试。"
                projection.safeFailureCode == "RATE_LIMITED" -> "请求较多，请稍后重试。"
                projection.safeFailureCode == "INSUFFICIENT_QUOTA" -> "阶跃星辰账户余额或套餐额度不足，请检查服务商账户。"
                projection.safeFailureCode == "INPUT_SENSITIVE" -> "输入内容未通过阶跃星辰安全检查，请调整后重新发送。"
                projection.safeFailureCode == "OUTPUT_SENSITIVE" -> "阶跃星辰未能安全生成回复，请换一种方式提问。"
                projection.safeFailureCode == "INVALID_REQUEST" -> "当前请求参数不受阶跃星辰支持，请调整内容后重试。"
                projection.safeFailureCode == "NETWORK_OFFLINE" -> "当前没有网络，仍可查看本地日程、联系人和记忆。连接网络后可重试对话。"
                projection.safeFailureCode == "NETWORK_TOO_SLOW" -> "当前网络较差，AI 对话暂不可用。"
                projection.safeFailureCode == "WEAK_NETWORK_MULTIMODAL_DISABLED" -> "网络较慢，图片和文件分析已暂停，请恢复网络后重试。"
                projection.safeFailureCode in setOf("TIMEOUT", "PROVIDER_UNAVAILABLE") -> "暂时无法连接大模型，请检查网络后重试。"
                projection.safeFailureCode == "EMPTY_RESPONSE" -> "AI 没有返回内容，请重新发送。"
                projection.safeFailureCode == "INVALID_TOOL_CALL" -> "AI 没有生成可安全执行的操作，请换一种说法重新发送。"
                projection.safeFailureCode == "TARGET_APP_UNAVAILABLE" -> "没有找到目标消息应用，或该应用暂时无法打开。请确认已安装并登录后重试。"
                projection.safeFailureCode != null -> "大模型暂时无法完成请求，请稍后重试。"
                else -> null
            },
            pendingProposalId = projection.pendingApproval?.proposalId,
            plan = projection.pendingApproval?.let { approval ->
                val isExternalMessage = approval.platform?.isNotBlank() == true &&
                    approval.recipient?.isNotBlank() == true
                AgentPlanUi(
                    title = approval.title.ifBlank { "执行知伴计划" },
                    subject = approval.title,
                    schedule = formatScheduleLine(
                        approval.scheduleStartAtEpochMs,
                        approval.scheduleDurationMinutes,
                        approval.scheduleNote,
                    ),
                    reminder = formatReminderLine(approval.scheduleReminderMinutesBefore),
                    platform = approval.platform.orEmpty(),
                    recipient = approval.recipient.orEmpty(),
                    // Several internal tools use a payload field named `message` as their
                    // confirmation summary. It is only an outbound message when the plan also
                    // carries an explicit platform and recipient. Treating every such field as
                    // outbound made CRM cards claim "将发送 / 打开目标应用".
                    message = approval.message.orEmpty().takeIf { isExternalMessage }.orEmpty(),
                    details = approval.details.orEmpty().ifBlank {
                        approval.message.orEmpty().takeUnless { isExternalMessage }.orEmpty()
                    },
                )
            },
            usedTokens = projection.budget?.usedTokens,
            maxTokens = projection.budget?.maxTokens,
            sourceLabels = projection.sources.map { it.label },
            canCancel = RuntimeAction.CANCEL in projection.allowedActions,
            canResume = RuntimeAction.RESUME in projection.allowedActions,
            canUndo = projection.undoAvailable && RuntimeAction.UNDO in projection.allowedActions,
            inputEnabled = projection.runStatus in INPUT_ENABLED_STATUSES,
            isCredentialMissing =
                projection.safeFailureCode in setOf("PROVIDER_NOT_CONFIGURED", "AUTHENTICATION_FAILED"),
            safeFailureCode = projection.safeFailureCode,
        )
    }

    private fun RuntimeRunStatus.toUiStage(): AgentConversationStage = when (this) {
        RuntimeRunStatus.RECEIVED,
        RuntimeRunStatus.ASSEMBLING_CONTEXT,
        RuntimeRunStatus.INFERENCING,
        RuntimeRunStatus.VALIDATING_PLAN,
        -> AgentConversationStage.PLANNING

        RuntimeRunStatus.AWAITING_CONFIRMATION -> AgentConversationStage.AWAITING_CONFIRMATION

        RuntimeRunStatus.EXECUTING,
        RuntimeRunStatus.OBSERVING,
        RuntimeRunStatus.CANCEL_REQUESTED,
        -> AgentConversationStage.EXECUTING

        RuntimeRunStatus.SUCCEEDED -> AgentConversationStage.SUCCEEDED

        RuntimeRunStatus.CANCELLED -> AgentConversationStage.CANCELLED

        RuntimeRunStatus.FAILED_RETRYABLE -> AgentConversationStage.FAILED_RETRYABLE

        RuntimeRunStatus.FAILED_FINAL -> AgentConversationStage.FAILED_FINAL
    }

    private val INPUT_ENABLED_STATUSES = setOf(
        RuntimeRunStatus.SUCCEEDED,
        RuntimeRunStatus.CANCELLED,
        RuntimeRunStatus.FAILED_RETRYABLE,
        RuntimeRunStatus.FAILED_FINAL,
    )

    // "8月10日 10:00 · 60分钟" plus an optional note line, so the user can verify the resolved
    // date/time/duration before confirming a schedule write. Blank when there is nothing to show.
    private fun formatScheduleLine(startAtEpochMs: Long?, durationMinutes: Int?, note: String?): String {
        if (startAtEpochMs == null) return ""
        val start = DateFormats.MonthDayTime.format(Instant.ofEpochMilli(startAtEpochMs).atZone(ZoneId.systemDefault()))
        val duration = durationMinutes?.let { " · ${it}分钟" }.orEmpty()
        val noteLine = note?.takeIf(String::isNotBlank)?.let { "\n备注：$it" }.orEmpty()
        return "$start$duration$noteLine"
    }

    private fun formatReminderLine(reminderMinutesBefore: Int?): String = when (reminderMinutesBefore) {
        null -> ""
        1_440 -> "提前 1 天提醒"
        else -> "提前 $reminderMinutesBefore 分钟提醒"
    }
}
