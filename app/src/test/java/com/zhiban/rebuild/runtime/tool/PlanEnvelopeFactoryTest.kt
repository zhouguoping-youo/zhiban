package com.zhiban.rebuild.runtime.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanEnvelopeFactoryTest {
    @Test
    fun `same provider call is stable and retry-safe`() {
        val request = RuntimeToolCallRequest("call-1", "tool", "{}")
        val context = RuntimeToolRouteContext("run", "session", "attempt", "owner", 3, 7, 9)

        val first = PlanEnvelopeFactory.create(request, context, "mcp.server.tool", "digest")
        val replay = PlanEnvelopeFactory.create(request, context, "mcp.server.tool", "digest")

        assertEquals(first, replay)
        assertTrue(first.idempotencyKey.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `provider call participates in idempotency identity`() {
        val context = RuntimeToolRouteContext("run", "session", "attempt", "owner", 3, 7, 9)
        val first = PlanEnvelopeFactory.create(
            RuntimeToolCallRequest("call-1", "tool", "{}"),
            context,
            "tool",
            "digest",
        )
        val second = PlanEnvelopeFactory.create(
            RuntimeToolCallRequest("call-2", "tool", "{}"),
            context,
            "tool",
            "digest",
        )

        assertNotEquals(first.idempotencyKey, second.idempotencyKey)
    }

    @Test
    fun `canonical input digest is size-prefixed stable`() {
        val first = PlanEnvelopeFactory.canonicalInputDigest("abc")
        val second = PlanEnvelopeFactory.canonicalInputDigest("abc")
        assertEquals(first, second)
        assertEquals(64, first.length)
    }
}
