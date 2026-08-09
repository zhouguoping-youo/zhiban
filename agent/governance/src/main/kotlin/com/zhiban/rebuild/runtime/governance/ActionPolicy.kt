package com.zhiban.rebuild.runtime.governance

import com.zhiban.rebuild.runtime.tool.RuntimeToolRisk
import com.zhiban.rebuild.runtime.tool.RuntimeToolSpec

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
    }
}

/** Pure policy authority. Tool bindings and providers cannot override its decision. */
class ActionPolicy {
    fun evaluate(
        spec: RuntimeToolSpec,
        confirmationGranted: Boolean = false,
        reversibleWriteReadiness: ReversibleWriteReadiness = ReversibleWriteReadiness.Unavailable,
    ): ActionDecision = when (spec.risk) {
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
