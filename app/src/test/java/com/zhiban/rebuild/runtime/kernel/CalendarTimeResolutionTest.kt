package com.zhiban.rebuild.runtime.kernel

import com.zhiban.rebuild.runtime.context.LocalEntityExtractor
import com.zhiban.rebuild.runtime.provider.ModelEvent
import com.zhiban.rebuild.runtime.tool.SchedulePlanValidator
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Deep tests for the calendar time-understanding + local-override path: the deterministic
 * fallback must compute the correct device-zone epoch, and normalizeCalendarToolCall must
 * correct a wrong provider-supplied epoch (the "时间搞错" risk) instead of trusting it.
 */
class CalendarTimeResolutionTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    // Friday 2026-08-07 09:00 +08:00 — "明天" = 2026-08-08.
    private val now = LocalDateTime.of(2026, 8, 7, 9, 0).atZone(zone).toInstant().toEpochMilli()
    private val extractor = LocalEntityExtractor(zone)
    private val expectedTomorrow3pm =
        LocalDateTime.of(2026, 8, 8, 15, 0).atZone(zone).toInstant().toEpochMilli()

    @Test fun deterministicFallbackComputesLocalTomorrow3pm() {
        val queryContext = extractor.extract("明天下午3点和张总开会", "Work", now)
        val toolCall = deterministicCalendarToolCall(DecodedInput("明天下午3点和张总开会", "Work"), queryContext)
        assertNotNull("deterministic path should produce a tool call", toolCall)
        assertEquals(expectedTomorrow3pm, toolCall!!.startAt())
    }

    @Test fun normalizeOverridesWrongProviderEpochWithLocalTomorrow3pm() {
        val queryContext = extractor.extract("明天下午3点和张总开会", "Work", now)
        // Provider returns tomorrow 03:00 (AM/PM confusion). The local override must correct it to 15:00.
        val wrongModelValue = LocalDateTime.of(2026, 8, 8, 3, 0).atZone(zone).toInstant().toEpochMilli()
        val modelCall = ModelEvent.ToolCall(
            ordinal = 0L,
            providerCallId = "call-1",
            name = SchedulePlanValidator.TOOL_NAME,
            argumentsJson = """{"title":"和张总开会","startAtEpochMs":$wrongModelValue,"durationMinutes":60}""",
        )
        val normalized = normalizeCalendarToolCall({ it }, modelCall, DecodedInput("明天下午3点和张总开会", "Work"), queryContext)
        assertEquals(expectedTomorrow3pm, normalized.startAt())
    }

    @Test fun normalizeKeepsCorrectProviderEpoch() {
        val queryContext = extractor.extract("明天下午3点和张总开会", "Work", now)
        val modelCall = ModelEvent.ToolCall(
            ordinal = 0L,
            providerCallId = "call-1",
            name = SchedulePlanValidator.TOOL_NAME,
            argumentsJson = """{"title":"和张总开会","startAtEpochMs":$expectedTomorrow3pm,"durationMinutes":60}""",
        )
        val normalized = normalizeCalendarToolCall({ it }, modelCall, DecodedInput("明天下午3点和张总开会", "Work"), queryContext)
        assertEquals(expectedTomorrow3pm, normalized.startAt())
    }

    private fun ModelEvent.ToolCall.startAt(): Long = Json.parseToJsonElement(argumentsJson).jsonObject["startAtEpochMs"]!!.jsonPrimitive.long
}
