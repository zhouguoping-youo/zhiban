package com.zhiban.rebuild.data.autowrite

import com.zhiban.rebuild.data.autowrite.ActionPolicy
import com.zhiban.rebuild.data.autowrite.ReversibleWriteReadiness
import com.zhiban.rebuild.foundation.RuntimeToolRisk
import com.zhiban.rebuild.foundation.RuntimeToolSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionPolicyTest {
    private val policy = ActionPolicy()

    @Test fun readsAutoExecuteButWritesNeverDo() {
        assertEquals(ActionDecision.AutoExecute, policy.evaluate(RuntimeToolRisk.READ_ONLY))
        assertTrue(policy.evaluate(RuntimeToolRisk.REVERSIBLE_AUTO_WRITE) is ActionDecision.RequireConfirmation)
        assertTrue(
            policy.evaluate(RuntimeToolRisk.WRITE_CONFIRMATION_REQUIRED) is ActionDecision.RequireConfirmation,
        )
        assertTrue(policy.evaluate(RuntimeToolRisk.HIGH_RISK) is ActionDecision.RequireConfirmation)
    }

    @Test fun confirmedWriteIsAllowedAndHighRiskKeepsStrongFlag() {
        assertEquals(
            ActionDecision.AllowedAfterConfirmation(false),
            policy.evaluate(RuntimeToolRisk.WRITE_CONFIRMATION_REQUIRED, true),
        )
        assertEquals(
            ActionDecision.AllowedAfterConfirmation(false),
            policy.evaluate(RuntimeToolRisk.REVERSIBLE_AUTO_WRITE, true),
        )
        assertEquals(
            ActionDecision.AllowedAfterConfirmation(true),
            policy.evaluate(RuntimeToolRisk.HIGH_RISK, true),
        )
    }

    @Test fun reversibleWriteAutoExecutesOnlyWhenAllThreeGuaranteesExist() {
        val ready = ReversibleWriteReadiness(true, true, true)
        assertEquals(
            ActionDecision.AutoExecuteReversibleWrite,
            policy.evaluate(RuntimeToolRisk.REVERSIBLE_AUTO_WRITE, reversibleWriteReadiness = ready),
        )
        listOf(
            ReversibleWriteReadiness(false, true, true) to "auto_write:inverse_unavailable",
            ReversibleWriteReadiness(true, false, true) to "auto_write:audit_unavailable",
            ReversibleWriteReadiness(true, true, false) to "auto_write:undo_surface_unavailable",
        ).forEach { (readiness, reason) ->
            assertEquals(
                ActionDecision.RequireConfirmation(false, reason),
                policy.evaluate(RuntimeToolRisk.REVERSIBLE_AUTO_WRITE, reversibleWriteReadiness = readiness),
            )
        }
    }

    @Test fun readinessCannotPromoteConfirmedOrHighRiskTools() {
        val ready = ReversibleWriteReadiness(true, true, true)
        assertTrue(
            policy.evaluate(
                RuntimeToolRisk.WRITE_CONFIRMATION_REQUIRED,
                reversibleWriteReadiness = ready,
            ) is ActionDecision.RequireConfirmation,
        )
        assertTrue(
            policy.evaluate(
                RuntimeToolRisk.HIGH_RISK,
                reversibleWriteReadiness = ready,
            ) is ActionDecision.RequireConfirmation,
        )
    }
}
