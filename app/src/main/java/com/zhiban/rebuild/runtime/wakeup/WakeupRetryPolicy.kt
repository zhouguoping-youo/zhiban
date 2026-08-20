package com.zhiban.rebuild.runtime.wakeup

/** Bounds a single wakeup cycle to at most one retry after a transient provider failure. */
internal object WakeupRetryPolicy {
    enum class Decision { RETRY_ONCE, SKIP_CYCLE }

    fun afterRetryableFailure(failureCount: Int): Decision {
        require(failureCount > 0) { "failureCount must be positive" }
        return if (failureCount < MAX_RETRYABLE_FAILURES) Decision.RETRY_ONCE else Decision.SKIP_CYCLE
    }

    private const val MAX_RETRYABLE_FAILURES = 2
}
