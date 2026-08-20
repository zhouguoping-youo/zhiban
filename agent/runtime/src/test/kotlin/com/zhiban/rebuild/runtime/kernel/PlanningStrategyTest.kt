package com.zhiban.rebuild.runtime.kernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanningStrategyTest {
    @Test fun coworkerMapsAllExecutionPreferences() {
        assertEquals(PlanningStrategy.DIRECT, PlanningStrategySelector.select("快速").strategy)
        assertEquals(PlanningStrategy.REACT, PlanningStrategySelector.select("标准").strategy)
        val deep = PlanningStrategySelector.select("深入")
        assertEquals(PlanningStrategy.PLAN_THEN_EXECUTE, deep.strategy)
        assertTrue(deep.instruction.contains("不得绕过确认"))
        assertEquals(PlanningStrategy.DIRECT, PlanningStrategySelector.select("极速 (5.5)").strategy)
    }
}
