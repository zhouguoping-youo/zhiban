package com.zhiban.rebuild.runtime.wakeup

import org.junit.Assert.assertEquals
import org.junit.Test

class WakeupRetryPolicyTest {
    @Test
    fun `first retryable failure gets one bounded retry`() {
        assertEquals(WakeupRetryPolicy.Decision.RETRY_ONCE, WakeupRetryPolicy.afterRetryableFailure(1))
    }

    @Test
    fun `second retryable failure skips current cycle`() {
        assertEquals(WakeupRetryPolicy.Decision.SKIP_CYCLE, WakeupRetryPolicy.afterRetryableFailure(2))
    }
}
