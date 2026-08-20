package com.zhiban.rebuild.data.autowrite

import com.zhiban.rebuild.foundation.RuntimeToolRisk

sealed interface ActionDecision {
    data object AutoExecute : ActionDecision
    data object AutoExecuteReversibleWrite : ActionDecision
    data class RequireConfirmation(val strong: Boolean, val reason: String) : ActionDecision
    data class AllowedAfterConfirmation(val strong: Boolean) : ActionDecision
    data class Blocked(val reason: String) : ActionDecision
}

data class ReversibleWriteReadiness(
    val inverseSupported: Boolean,
    val atomicChangeLogSupported: Boolean,
    val visibleUndoSupported: Boolean,
    val rejectionReason: String? = null,
) {
    val ready: Boolean
        get() = inverseSupported && atomicChangeLogSupported && visibleUndoSupported && rejectionReason == null

    fun reasonCode(): String = rejectionReason ?: when {
        !inverseSupported -> "auto_write:inverse_unavailable"
        !atomicChangeLogSupported -> "auto_write:audit_unavailable"
        !visibleUndoSupported -> "auto_write:undo_surface_unavailable"
        else -> "auto_write:policy_rejected"
    }

    companion object {
        val Unavailable = ReversibleWriteReadiness(false, false, false)

        /**
         * 全就绪：三项能力由治理层真实提供——
         * inverseSupported=ChangeUndoCoordinator 支持反操作；
         * atomicChangeLogSupported=change_log 表原子落账；
         * visibleUndoSupported=自动整理页提供撤销入口。
         * 若任一能力下线，必须改用 [Unavailable] 或带 rejectionReason 的实例，禁止继续声称就绪。
         */
        val Ready = ReversibleWriteReadiness(true, true, true)
    }
}

/** Pure policy authority. Tool bindings and providers cannot override its decision. */
class ActionPolicy {
    fun evaluate(
        risk: RuntimeToolRisk,
        confirmationGranted: Boolean = false,
        reversibleWriteReadiness: ReversibleWriteReadiness = ReversibleWriteReadiness.Unavailable,
    ): ActionDecision = when (risk) {
        RuntimeToolRisk.READ_ONLY -> ActionDecision.AutoExecute

        RuntimeToolRisk.REVERSIBLE_AUTO_WRITE -> when {
            confirmationGranted -> ActionDecision.AllowedAfterConfirmation(strong = false)

            reversibleWriteReadiness.ready -> ActionDecision.AutoExecuteReversibleWrite

            else -> ActionDecision.RequireConfirmation(
                strong = false,
                reason = reversibleWriteReadiness.reasonCode(),
            )
        }

        RuntimeToolRisk.WRITE_CONFIRMATION_REQUIRED -> if (confirmationGranted) {
            ActionDecision.AllowedAfterConfirmation(strong = false)
        } else {
            ActionDecision.RequireConfirmation(strong = false, reason = "写入用户数据前需要确认")
        }

        RuntimeToolRisk.HIGH_RISK -> if (confirmationGranted) {
            ActionDecision.AllowedAfterConfirmation(strong = true)
        } else {
            ActionDecision.RequireConfirmation(strong = true, reason = "高风险或不可逆操作需要强确认")
        }
    }
}
