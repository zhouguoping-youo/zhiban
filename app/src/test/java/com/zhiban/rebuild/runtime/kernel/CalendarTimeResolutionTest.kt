package com.zhiban.rebuild.runtime.kernel

import com.zhiban.rebuild.runtime.context.LocalEntityExtractor
import com.zhiban.rebuild.runtime.network.NetworkQuality
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

    @Test fun normalizeReplacesGenericModelTitleWithTheUsersActualTask() {
        val text = "提醒我明天晚上8点接孩子"
        val queryContext = extractor.extract(text, "Work", now)
        val modelCall = ModelEvent.ToolCall(
            ordinal = 0L,
            providerCallId = "call-generic-title",
            name = SchedulePlanValidator.TOOL_NAME,
            argumentsJson =
                """{"title":"日程安排","startAtEpochMs":$expectedTomorrow3pm,"durationMinutes":60}""",
        )

        val normalized = normalizeCalendarToolCall({ it }, modelCall, DecodedInput(text, "Work"), queryContext, now)

        assertEquals("接孩子", normalized.title())
    }

    @Test fun normalizeReplacesGenericReminderTitleAfterSanitization() {
        val text = "提醒我明天晚上8点接孩子"
        val queryContext = extractor.extract(text, "Work", now)
        val modelCall = ModelEvent.ToolCall(
            ordinal = 0L,
            providerCallId = "call-reminder-title",
            name = SchedulePlanValidator.TOOL_NAME,
            argumentsJson =
                """{"title":"提醒事项","startAtEpochMs":$expectedTomorrow3pm,"durationMinutes":60}""",
        )

        val normalized = normalizeCalendarToolCall({ it }, modelCall, DecodedInput(text, "Work"), queryContext, now)

        assertEquals("接孩子", normalized.title())
    }

    @Test fun deterministicTitleKeepsTheCounterpartyRelationshipNatural() {
        val text = "明天下午3点和张总开武汉项目复盘会"
        val queryContext = extractor.extract(text, "Work", now)

        val toolCall = requireNotNull(deterministicCalendarToolCall(DecodedInput(text, "Work"), queryContext, now))

        assertEquals("和张总开武汉项目复盘会", toolCall.title())
    }

    @Test fun deterministicTitleDoesNotMistakeAPlaceStartingWithHeForAPerson() {
        val text = "明天下午3点在和平饭店开会"
        val queryContext = extractor.extract(text, "Work", now)

        val toolCall = requireNotNull(deterministicCalendarToolCall(DecodedInput(text, "Work"), queryContext, now))

        assertEquals("在和平饭店开会", toolCall.title())
    }

    @Test fun deterministicTitleKeepsTheConcreteObjectAndAction() {
        val text = "明天晚上7点给王经理发送武汉医院项目最终报价单"
        val queryContext = extractor.extract(text, "Work", now)

        val toolCall = requireNotNull(deterministicCalendarToolCall(DecodedInput(text, "Work"), queryContext, now))

        assertEquals("给王经理发送武汉医院项目最终报价单", toolCall.title())
    }

    @Test fun deterministicCalendarCallKeepsEnglishTitledNameAndExcludesReminderClause() {
        val text = "Create a calendar schedule tomorrow at 4 PM titled Resume Confirmation Test with reminder 10 minutes before"
        val queryContext = extractor.extract(text, "Work", now)

        val toolCall = requireNotNull(deterministicCalendarToolCall(DecodedInput(text, "Work"), queryContext, now))
        val arguments = Json.parseToJsonElement(toolCall.argumentsJson).jsonObject

        assertEquals("Resume Confirmation Test", arguments.getValue("title").jsonPrimitive.content)
        assertEquals(10, arguments.getValue("reminderMinutesBefore").jsonPrimitive.content.toInt())
    }

    @Test fun reactTimeoutKeepsMultimodalAndWeakNetworkBudgets() {
        assertEquals(90_000L, reactTimeoutMs(true, 30, NetworkQuality.NORMAL, 120_000L))
        assertEquals(15_000L, reactTimeoutMs(false, 30, NetworkQuality.WEAK, 120_000L))
        assertEquals(30_000L, reactTimeoutMs(false, 30, NetworkQuality.NORMAL, 120_000L))
    }

    private fun ModelEvent.ToolCall.startAt(): Long = Json.parseToJsonElement(argumentsJson).jsonObject["startAtEpochMs"]!!.jsonPrimitive.long

    private fun ModelEvent.ToolCall.title(): String = Json.parseToJsonElement(argumentsJson).jsonObject.getValue("title").jsonPrimitive.content
}
