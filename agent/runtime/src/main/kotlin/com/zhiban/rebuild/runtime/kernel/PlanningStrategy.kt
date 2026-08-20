package com.zhiban.rebuild.runtime.kernel

enum class PlanningStrategy {
    DIRECT,
    REACT,
    PLAN_THEN_EXECUTE,
}

data class PlanningPolicy(val strategy: PlanningStrategy, val instruction: String)

/** Converts the user-facing execution preference into an explicit, testable planning policy. */
object PlanningStrategySelector {
    fun select(level: String?): PlanningPolicy {
        val normalized = level.orEmpty().trim()
        return when {
            normalized in setOf("快速", "极速 (5.5)") -> PlanningPolicy(
                PlanningStrategy.DIRECT,
                "采用单步执行：只在确有必要时调用一个最匹配工具；写操作仍须用户确认。",
            )

            normalized in setOf("深入", "高") -> PlanningPolicy(
                PlanningStrategy.PLAN_THEN_EXECUTE,
                "采用先规划后执行：先分解目标与依赖，再逐步调用工具并根据观察修正；不得绕过确认。",
            )

            else -> PlanningPolicy(
                PlanningStrategy.REACT,
                "采用 ReAct 循环：判断下一步、调用必要工具、读取观察后继续，直到可验证地完成；不得绕过确认。",
            )
        }
    }
}
