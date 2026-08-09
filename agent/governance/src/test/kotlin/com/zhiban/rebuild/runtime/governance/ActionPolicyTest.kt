package com.zhiban.rebuild.runtime.governance

import com.zhiban.rebuild.runtime.tool.RuntimeToolRisk
import com.zhiban.rebuild.runtime.tool.RuntimeToolSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionPolicyTest {
    private val policy = ActionPolicy()
    private fun spec(risk: RuntimeToolRisk) = RuntimeToolSpec("tool", 1, risk, "{}", 1)

    @Test fun readsAutoExecuteButWritesNeverDo() {
        assertEquals(ActionDecision.AutoExecute, policy.evaluate(spec(RuntimeToolRisk.READ_ONLY)))
        assertTrue(policy.evaluate(spec(RuntimeToolRisk.REVERSIBLE_AUTO_WRITE)) is ActionDecision.RequireConfirmation)
        assertTrue(
            policy.evaluate(spec(RuntimeToolRisk.WRITE_CONFIRMATION_REQUIRED)) is ActionDecision.RequireConfirmation,
        )
        assertTrue(policy.evaluate(spec(RuntimeToolRisk.HIGH_RISK)) is ActionDecision.RequireConfirmation)
    }

    @Test fun confirmedWriteIsAllowedAndHighRiskKeepsStrongFlag() {
        assertEquals(
            ActionDecision.AllowedAfterConfirmation(false),
            policy.evaluate(spec(RuntimeToolRisk.WRITE_CONFIRMATION_REQUIRED), true),
        )
        assertEquals(
            ActionDecision.AllowedAfterConfirmation(false),
            policy.evaluate(spec(RuntimeToolRisk.REVERSIBLE_AUTO_WRITE), true),
        )
        assertEquals(
            ActionDecision.AllowedAfterConfirmation(true),
            policy.evaluate(spec(RuntimeToolRisk.HIGH_RISK), true),
        )
    }

    @Test fun reversibleWriteAutoExecutesOnlyWhenAllThreeGuaranteesExist() {
        val ready = ReversibleWriteReadiness(true, true, true)
        assertEquals(
            ActionDecision.AutoExecuteReversibleWrite,
            policy.evaluate(spec(RuntimeToolRisk.REVERSIBLE_AUTO_WRITE), reversibleWriteReadiness = ready),
        )
        listOf(
            ReversibleWriteReadiness(false, true, true) to "auto_write:inverse_unavailable",
            ReversibleWriteReadiness(true, false, true) to "auto_write:audit_unavailable",
            ReversibleWriteReadiness(true, true, false) to "auto_write:undo_surface_unavailable",
        ).forEach { (readiness, reason) ->
            assertEquals(
                ActionDecision.RequireConfirmation(false, reason),
                policy.evaluate(spec(RuntimeToolRisk.REVERSIBLE_AUTO_WRITE), reversibleWriteReadiness = readiness),
            )
        }
    }

    @Test fun readinessCannotPromoteConfirmedOrHighRiskTools() {
        val ready = ReversibleWriteReadiness(true, true, true)
        assertTrue(
            policy.evaluate(
                spec(RuntimeToolRisk.WRITE_CONFIRMATION_REQUIRED),
                reversibleWriteReadiness = ready,
            ) is ActionDecision.RequireConfirmation,
        )
        assertTrue(
            policy.evaluate(
                spec(RuntimeToolRisk.HIGH_RISK),
                reversibleWriteReadiness = ready,
            ) is ActionDecision.RequireConfirmation,
        )
    }
}
