package com.zhiban.rebuild.runtime.kernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanningStrategyTest {
    @Test fun chatAlwaysUsesDirectAnswerPolicy() {
        assertEquals(PlanningStrategy.DIRECT, PlanningStrategySelector.select("Chat", "高").strategy)
    }

    @Test fun workMapsAllExecutionPreferences() {
        assertEquals(PlanningStrategy.DIRECT, PlanningStrategySelector.select("Work", "快速").strategy)
        assertEquals(PlanningStrategy.REACT, PlanningStrategySelector.select("Work", "标准").strategy)
        val deep = PlanningStrategySelector.select("Work", "深入")
        assertEquals(PlanningStrategy.PLAN_THEN_EXECUTE, deep.strategy)
        assertTrue(deep.instruction.contains("不得绕过确认"))
        assertEquals(PlanningStrategy.DIRECT, PlanningStrategySelector.select("Work", "极速 (5.5)").strategy)
    }
}
