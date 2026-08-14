package com.zhiban.rebuild.runtime.governance

import com.zhiban.rebuild.runtime.provider.OutboundAuditEvent
import com.zhiban.rebuild.runtime.provider.OutboundChannel
import com.zhiban.rebuild.runtime.provider.OutboundPurpose
import com.zhiban.rebuild.runtime.provider.OutboundSensitivity
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class OutboundAuditAggregationTest {
    private val zoneId = ZoneId.of("Asia/Shanghai")

    @Test fun monthlyProtectionCountsAccumulateWithinMonthAndResetAtMonthBoundary() {
        val july = event("2026-07-31T15:59:00Z", redacted = 2, omitted = 1)
        val august = event("2026-07-31T16:01:00Z", redacted = 3, omitted = 4)

        val first = updateMonthlyProtection(null, july, zoneId)
        val accumulated = updateMonthlyProtection(first, july.copy(redactedMessageCount = 1), zoneId)
        val reset = updateMonthlyProtection(accumulated, august, zoneId)

        assertEquals(3, accumulated.redacted)
        assertEquals(2, accumulated.omitted)
        assertEquals("2026-08", reset.month)
        assertEquals(3, reset.redacted)
        assertEquals(4, reset.omitted)
    }

    private fun event(instant: String, redacted: Int, omitted: Int) = OutboundAuditEvent(
        requestId = "safe-test-id",
        channel = OutboundChannel.LLM_INFERENCE,
        purposes = setOf(OutboundPurpose.AUTO_RETRIEVED),
        sensitivities = setOf(OutboundSensitivity.PERSONAL),
        messageCount = 1,
        attachmentCount = 0,
        redactedMessageCount = redacted,
        omittedMessageCount = omitted,
        occurredAtEpochMs = Instant.parse(instant).toEpochMilli(),
    )
}
