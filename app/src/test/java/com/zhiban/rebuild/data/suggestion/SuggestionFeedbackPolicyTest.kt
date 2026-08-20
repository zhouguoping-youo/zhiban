package com.zhiban.rebuild.data.suggestion

import org.junit.Assert.assertEquals
import org.junit.Test

class SuggestionFeedbackPolicyTest {
    @Test fun acceptedHistoryRaisesPriorityAndShortensThrottle() {
        val adjustment = SuggestionFeedbackPolicy.adjustment(AgentSuggestionFeedbackStats(3, 0))

        assertEquals(70, adjustment.adjustedPriority(DEFAULT_SUGGESTION_PRIORITY))
        assertEquals(50, adjustment.throttleWindowPercent)
    }

    @Test fun threeDismissalsLowerPriorityAndSlowFutureWakeups() {
        val adjustment = SuggestionFeedbackPolicy.adjustment(AgentSuggestionFeedbackStats(0, 3))

        assertEquals(30, adjustment.adjustedPriority(DEFAULT_SUGGESTION_PRIORITY))
        assertEquals(400, adjustment.throttleWindowPercent)
    }

    @Test fun sparseFeedbackDoesNotOverfit() {
        assertEquals(
            SuggestionFeedbackAdjustment(),
            SuggestionFeedbackPolicy.adjustment(AgentSuggestionFeedbackStats(0, 2)),
        )
    }
}
