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

    @Test fun exactUserPhraseResolvesTomorrowAt10pmWithAConcreteTitle() {
        val reportedNow = LocalDateTime.of(2026, 8, 14, 8, 0).atZone(zone).toInstant().toEpochMilli()
        val text = "让agent创建一个 明晚10点与委内瑞拉客户会议的日程提醒"
        val queryContext = extractor.extract(text, "Work", reportedNow)

        val toolCall = requireNotNull(
            deterministicCalendarToolCall(DecodedInput(text, "Work"), queryContext, reportedNow),
        )

        assertEquals(
            LocalDateTime.of(2026, 8, 15, 22, 0).atZone(zone).toInstant().toEpochMilli(),
            toolCall.startAt(),
        )
        assertEquals("与委内瑞拉客户会议", toolCall.title())
        assertEquals(60, toolCall.intArgument("durationMinutes"))
        assertEquals(10, toolCall.intArgument("reminderMinutesBefore"))
    }

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

    @Test fun deterministicCalendarCallHonorsExplicitDurationInsteadOfInventingOne() {
        val text = "明晚10点与委内瑞拉客户开会，时长90分钟"
        val queryContext = extractor.extract(text, "Work", now)

        val toolCall = requireNotNull(deterministicCalendarToolCall(DecodedInput(text, "Work"), queryContext, now))

        assertEquals(90, toolCall.intArgument("durationMinutes"))
    }

    @Test fun naturalChineseTimeDurationIsNotMistakenForA60MinuteDefault() {
        val text = "帮我安排明天晚上10点与委内瑞拉的客户的视频会议，时间30分钟，提前10分钟提醒我"
        val queryContext = extractor.extract(text, "Work", now)

        val toolCall = requireNotNull(deterministicCalendarToolCall(DecodedInput(text, "Work"), queryContext, now))

        assertEquals(30, toolCall.intArgument("durationMinutes"))
        assertEquals(10, toolCall.intArgument("reminderMinutesBefore"))
    }

    @Test fun deterministicCalendarCallKeepsAnExplicitCustomReminderOffset() {
        val text = "明天晚上8点复盘，提前15分钟提醒我"
        val queryContext = extractor.extract(text, "Work", now)

        val toolCall = requireNotNull(deterministicCalendarToolCall(DecodedInput(text, "Work"), queryContext, now))

        assertEquals(15, toolCall.intArgument("reminderMinutesBefore"))
    }

    @Test fun exactConflictQuestionBuildsAReadOnlyCheckForTomorrowAt10pm() {
        val reportedNow = LocalDateTime.of(2026, 8, 14, 8, 0).atZone(zone).toInstant().toEpochMilli()
        val text = "明天有晚上10点的会议冲突吗"
        val queryContext = extractor.extract(text, "Work", reportedNow)

        val toolCall = requireNotNull(
            deterministicCalendarConflictToolCall(DecodedInput(text, "Work"), queryContext, reportedNow),
        )

        assertEquals("calendar.schedule.conflicts", toolCall.name)
        assertEquals(
            LocalDateTime.of(2026, 8, 15, 22, 0).atZone(zone).toInstant().toEpochMilli(),
            toolCall.startAt(),
        )
        assertEquals(60, toolCall.intArgument("durationMinutes"))
    }

    @Test fun unsupportedExplicitDurationDoesNotTakeTheDeterministicPath() {
        val text = "明晚10点与委内瑞拉客户开会，持续到谈完为止"
        val queryContext = extractor.extract(text, "Work", now)

        assertEquals(null, deterministicCalendarToolCall(DecodedInput(text, "Work"), queryContext, now))
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

    private fun ModelEvent.ToolCall.intArgument(name: String): Int =
        Json.parseToJsonElement(argumentsJson).jsonObject.getValue(name).jsonPrimitive.content.toInt()
}
