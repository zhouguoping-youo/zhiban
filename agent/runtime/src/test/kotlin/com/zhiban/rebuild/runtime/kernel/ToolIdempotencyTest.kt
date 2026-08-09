package com.zhiban.rebuild.runtime.kernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ToolIdempotencyTest {
    @Test
    fun retryWithSameCanonicalInputReusesKey() {
        val first = ToolIdempotency.key("r1", "step1", "calendar.create", 1, "digest-a")
        val retry = ToolIdempotency.key("r1", "step1", "calendar.create", 1, "digest-a")
        assertEquals(first, retry)
    }

    @Test
    fun editedApprovedPayloadProducesDifferentKey() {
        val original = ToolIdempotency.key("r1", "step1", "calendar.create", 1, "digest-a")
        val edited = ToolIdempotency.key("r1", "step1", "calendar.create", 1, "digest-b")
        assertNotEquals(original, edited)
    }
}
