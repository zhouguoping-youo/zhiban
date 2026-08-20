package com.zhiban.rebuild.data.suggestion

/**
 * Converts local accept/dismiss history into bounded presentation and wake-up adjustments.
 * The policy never stores message text and never permanently blocks a contact: repeated dismissals
 * only make the next proactive judgement less frequent, while accepted suggestions recover quickly.
 */
internal object SuggestionFeedbackPolicy {
    fun adjustment(stats: AgentSuggestionFeedbackStats): SuggestionFeedbackAdjustment {
        val total = stats.acceptedCount + stats.dismissedCount
        if (total < MIN_SAMPLE_SIZE) return SuggestionFeedbackAdjustment()
        val acceptRate = stats.acceptedCount.toDouble() / total
        return when {
            stats.dismissedCount >= STRONG_DISMISS_THRESHOLD && acceptRate <= LOW_ACCEPT_RATE ->
                SuggestionFeedbackAdjustment(priorityDelta = -30, throttleWindowPercent = 1_200)

            stats.dismissedCount >= DISMISS_THRESHOLD && acceptRate <= LOW_ACCEPT_RATE ->
                SuggestionFeedbackAdjustment(priorityDelta = -20, throttleWindowPercent = 400)

            acceptRate >= HIGH_ACCEPT_RATE ->
                SuggestionFeedbackAdjustment(priorityDelta = 20, throttleWindowPercent = 50)

            else -> SuggestionFeedbackAdjustment()
        }
    }

    private const val MIN_SAMPLE_SIZE = 3
    private const val DISMISS_THRESHOLD = 3
    private const val STRONG_DISMISS_THRESHOLD = 6
    private const val LOW_ACCEPT_RATE = 0.25
    private const val HIGH_ACCEPT_RATE = 0.75
}

internal data class SuggestionFeedbackAdjustment(val priorityDelta: Int = 0, val throttleWindowPercent: Int = 100) {
    fun adjustedPriority(basePriority: Int): Int = (basePriority + priorityDelta).coerceIn(0, 100)
}

internal const val SUGGESTION_FEEDBACK_WINDOW_MS = 90L * 24 * 60 * 60 * 1_000
