package com.zhiban.rebuild.runtime.kernel

import com.zhiban.rebuild.provider.ModelEvent
import com.zhiban.rebuild.provider.ProviderFailure
import com.zhiban.rebuild.runtime.tool.CapabilityRouter
import com.zhiban.rebuild.runtime.tool.RoutedToolResult
import com.zhiban.rebuild.runtime.tool.RuntimeToolCallRequest
import com.zhiban.rebuild.runtime.tool.RuntimeToolRouteContext
import com.zhiban.rebuild.runtime.tool.SchedulePlanValidator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal data class CalendarConflictPreflight(val request: RuntimeToolCallRequest, val result: RoutedToolResult)

/** Checks the real local and device calendars before an Agent schedule proposal reaches approval. */
internal class CalendarConflictGuard(private val router: CapabilityRouter) {
    suspend fun inspectScheduleCreate(event: ModelEvent.ToolCall, context: RuntimeToolRouteContext): CalendarConflictPreflight? {
        if (router.canonicalName(event.name) != SchedulePlanValidator.TOOL_NAME) return null
        val arguments = runCatching { Json.parseToJsonElement(event.argumentsJson).jsonObject }
            .getOrElse { throw ProviderFailure("INVALID_TOOL_ARGUMENTS", false) }
        val startAt = arguments["startAtEpochMs"]?.jsonPrimitive?.content?.toLongOrNull()
            ?: throw ProviderFailure("INVALID_TOOL_ARGUMENTS", false)
        val durationMinutes = arguments["durationMinutes"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: throw ProviderFailure("INVALID_TOOL_ARGUMENTS", false)
        val request = RuntimeToolCallRequest(
            providerCallId = "preflight-${event.providerCallId}",
            name = "calendar.schedule.conflicts",
            argumentsJson = buildJsonObject {
                put("startAtEpochMs", startAt)
                put("durationMinutes", durationMinutes)
                arguments["scheduleId"]?.jsonPrimitive?.content?.let { put("excludeScheduleId", it) }
            }.toString(),
        )
        val result = router.executeReadOnly(request, context)
        val resultJson = runCatching { Json.parseToJsonElement(result.safeResultJson).jsonObject }
            .getOrElse { throw ProviderFailure("INVALID_TOOL_RESULT", false) }
        val hasConflict = resultJson["hasConflict"]?.jsonPrimitive?.content?.toBooleanStrictOrNull()
            ?: throw ProviderFailure("INVALID_TOOL_RESULT", false)
        return CalendarConflictPreflight(request, result).takeIf { hasConflict }
    }
}
