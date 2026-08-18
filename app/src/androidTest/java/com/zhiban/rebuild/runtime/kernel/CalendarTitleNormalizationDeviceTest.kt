package com.zhiban.rebuild.runtime.kernel

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhiban.rebuild.runtime.context.LocalEntityExtractor
import com.zhiban.rebuild.provider.ModelEvent
import com.zhiban.rebuild.runtime.tool.SchedulePlanValidator
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CalendarTitleNormalizationDeviceTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = LocalDateTime.of(2026, 8, 13, 9, 0).atZone(zone).toInstant().toEpochMilli()
    private val extractor = LocalEntityExtractor(zone)

    @Test fun genericProviderTitleFallsBackToTheConcreteUserTask() {
        val text = "提醒我明天晚上8点接孩子"
        val context = extractor.extract(text, "Work", now)
        val event = ModelEvent.ToolCall(
            ordinal = 0L,
            providerCallId = "device-generic-title",
            name = SchedulePlanValidator.TOOL_NAME,
            argumentsJson = """{"title":"提醒事项","startAtEpochMs":1,"durationMinutes":60}""",
        )

        val normalized = normalizeCalendarToolCall({ it }, event, DecodedInput(text, "Work"), context, now)

        assertEquals("接孩子", normalized.title())
    }

    @Test fun titlesPreservePeoplePlacesAndConcreteObjects() {
        assertEquals("和张总开武汉项目复盘会", deterministicTitle("明天下午3点和张总开武汉项目复盘会"))
        assertEquals("在和平饭店开会", deterministicTitle("明天下午3点在和平饭店开会"))
        assertEquals("给王经理发送武汉医院项目最终报价单", deterministicTitle("明天晚上7点给王经理发送武汉医院项目最终报价单"))
    }

    private fun deterministicTitle(text: String): String {
        val context = extractor.extract(text, "Work", now)
        return requireNotNull(deterministicCalendarToolCall(DecodedInput(text, "Work"), context, now)).title()
    }

    private fun ModelEvent.ToolCall.title(): String =
        Json.parseToJsonElement(argumentsJson).jsonObject.getValue("title").jsonPrimitive.content
}
