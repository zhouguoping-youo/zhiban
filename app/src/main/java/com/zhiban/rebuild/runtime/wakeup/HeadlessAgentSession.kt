package com.zhiban.rebuild.runtime.wakeup

import android.util.Log
import com.zhiban.rebuild.foundation.runSuspendCatching
import com.zhiban.rebuild.runtime.spi.CommandReceiptStatus
import com.zhiban.rebuild.runtime.spi.RuntimeRunStatus
import com.zhiban.rebuild.runtime.spi.RuntimeUiClient
import com.zhiban.rebuild.runtime.spi.TextInputGateway
import com.zhiban.rebuild.ui.agent.projection.AgentRuntimeProjectionController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 无 UI 执行会话：事件唤醒后，复用与用户对话完全相同的执行引擎（gateway → controller →
 * kernel）跑一次 LLM 判断。不依赖 Compose、不弹任何界面；需要用户确认的动作会被
 * 自动拒绝（转为建议卡交给用户，绝不在后台静默通过确认闸门——分级授权红线）。
 *
 * 会话产出：
 * - assistantText：LLM 的判断与建议正文（进入建议中心）
 * - pendingApproval：被自动拒绝的确认项（提示用户"有一件事需要你拍板"）
 * - lastChangeId：LLM 已执行的可逆写（收据由既有治理层落，可在自动整理撤销）
 */
internal class HeadlessAgentSession(private val client: RuntimeUiClient, private val textInputGateway: TextInputGateway, private val scope: CoroutineScope) {
    data class HeadlessResult(
        val finalStatus: RuntimeRunStatus,
        val assistantText: String,
        val pendingApprovalTitle: String?,
        val pendingApprovalDetails: String?,
        val changeCommitted: Boolean,
        val failureCode: String? = null,
    )

    suspend fun run(sessionId: String, systemInput: String): HeadlessResult {
        val controller = AgentRuntimeProjectionController(
            client = client,
            sessionId = sessionId,
            surfaceId = SURFACE_ID,
            scope = scope,
        )
        controller.initialize()
        try {
            val staged = textInputGateway.stage(systemInput)
            val receipt = controller.start(staged.inputRef)
            if (receipt.status != CommandReceiptStatus.ACCEPTED) {
                textInputGateway.discard(staged.inputRef)
                return HeadlessResult(
                    finalStatus = RuntimeRunStatus.FAILED_FINAL,
                    assistantText = "",
                    pendingApprovalTitle = null,
                    pendingApprovalDetails = null,
                    changeCommitted = false,
                    failureCode = "start_${receipt.status}",
                )
            }
            // 后台事件不需要确认时直接等终态；需要确认时拒绝并继续等终态（最多再等一次拒绝后的收尾）。
            var autoRejectedApproval: Pair<String, String?>? = null
            var rejectionAttempts = 0
            val terminal = withTimeoutOrNull(SESSION_TIMEOUT_MS) {
                while (true) {
                    val projection = controller.projection.first { it.runStatus != RuntimeRunStatus.RECEIVED }
                    if (projection.runStatus in TERMINAL_STATUSES) return@withTimeoutOrNull projection
                    if (projection.runStatus == RuntimeRunStatus.AWAITING_CONFIRMATION && rejectionAttempts < MAX_APPROVAL_REJECTIONS) {
                        val approval = projection.pendingApproval
                        if (approval != null) {
                            rejectionAttempts++
                            autoRejectedApproval = approval.title to approval.details
                            val receipt = runSuspendCatching { controller.reject() }.getOrNull()
                            if (receipt?.status != CommandReceiptStatus.ACCEPTED) {
                                // reject 未被接受（会话只读 / action 不允许 / 状态已漂移）：
                                // 继续等待只会空耗到 120s 超时——立即终止，由协调器降级为失败。
                                Log.w(TAG, "headless:reject_not_accepted status=${receipt?.status} run=${projection.runStatus}")
                                return@withTimeoutOrNull projection
                            }
                        }
                    }
                    // 等待状态发生任何变化（终态、新确认、失败都算），超时由外层兜底。
                    controller.projection.first { it.runStatus != projection.runStatus || it.revision != projection.revision }
                }
                @Suppress("UNREACHABLE_CODE")
                controller.projection.value
            }
            val final = terminal ?: controller.projection.value
            // 会话被遗弃：要么整体超时，要么拒绝动作未被引擎接受、状态机不可能再前进。
            // 两种情形都取消会话并报 FAILED，绝不把 AWAITING_CONFIRMATION 透传回协调器。
            val abandoned = terminal == null || final.runStatus == RuntimeRunStatus.AWAITING_CONFIRMATION
            if (abandoned) {
                runSuspendCatching { controller.cancel() }
            }
            val rejected = autoRejectedApproval
            return HeadlessResult(
                finalStatus = if (abandoned) RuntimeRunStatus.FAILED_FINAL else final.runStatus,
                assistantText = final.assistantText.orEmpty(),
                pendingApprovalTitle = rejected?.first,
                pendingApprovalDetails = rejected?.second,
                changeCommitted = final.lastChangeId != null,
                failureCode = when {
                    terminal == null -> "session_timeout"
                    final.runStatus == RuntimeRunStatus.AWAITING_CONFIRMATION -> "reject_unaccepted"
                    else -> final.safeFailureCode
                },
            )
        } finally {
            controller.close()
        }
    }

    private companion object {
        const val TAG = "HeadlessAgent"
        const val SURFACE_ID = "agent-wakeup"
        const val SESSION_TIMEOUT_MS = 120_000L
        const val MAX_APPROVAL_REJECTIONS = 3
        val TERMINAL_STATUSES = setOf(
            RuntimeRunStatus.SUCCEEDED,
            RuntimeRunStatus.CANCELLED,
            RuntimeRunStatus.FAILED_FINAL,
            RuntimeRunStatus.FAILED_RETRYABLE,
        )
    }
}
