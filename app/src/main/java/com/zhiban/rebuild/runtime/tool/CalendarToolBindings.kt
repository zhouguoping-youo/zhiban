package com.zhiban.rebuild.runtime.tool

import com.zhiban.rebuild.data.agent.ScheduleDao
import com.zhiban.rebuild.data.calendar.ExternalCalendarConflictSource
import com.zhiban.rebuild.runtime.provider.ProviderFailure
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal class CalendarSearchToolBinding(override val spec: RuntimeToolSpec, private val schedules: ScheduleDao) : RuntimeToolBinding {
    override val aliases: Set<String> = setOf("calendar.search", "schedule.search")

    override suspend fun requestApproval(request: RuntimeToolCallRequest, context: RuntimeToolRouteContext): Boolean =
        throw ToolPolicyRejectedException("read-only tools do not request approval")

    override suspend fun executeReadOnly(request: RuntimeToolCallRequest, context: RuntimeToolRouteContext): RoutedToolResult {
        val args = parseToolArgs(request.argumentsJson, setOf("fromEpochMs", "toEpochMs", "limit")) {
            ProviderFailure("INVALID_TOOL_ARGUMENTS", false)
        }
        val from = args["fromEpochMs"]?.jsonPrimitive?.content?.toLongOrNull()
            ?: throw ProviderFailure("INVALID_TOOL_ARGUMENTS", false)
        val to = args["toEpochMs"]?.jsonPrimitive?.content?.toLongOrNull()
            ?: throw ProviderFailure("INVALID_TOOL_ARGUMENTS", false)
        val limit = args["limit"]?.jsonPrimitive?.content?.toIntOrNull() ?: 20
        if (from < 0 || to < from || to - from > MAX_RANGE_MS || limit !in 1..50) {
            throw ProviderFailure("INVALID_TOOL_ARGUMENTS", false)
        }
        val rows = schedules.listRange(from, to, limit)
        val safe = buildJsonObject {
            put("count", rows.size)
            put(
                "items",
                buildJsonArray {
                    rows.forEach { row ->
                        add(
                            buildJsonObject {
                                put("id", row.id)
                                put("title", row.title)
                                put("startAtEpochMs", row.startAtEpochMs)
                                put("durationMinutes", row.durationMinutes)
                                row.note?.let { put("note", it.take(500)) }
                            },
                        )
                    }
                },
            )
        }.toString()
        return RoutedToolResult(spec.name, request.providerCallId, safe)
    }

    private companion object {
        const val MAX_RANGE_MS = 366L * 24 * 60 * 60 * 1_000
    }
}

internal class CalendarConflictToolBinding(
    override val spec: RuntimeToolSpec,
    private val schedules: ScheduleDao,
    private val externalConflicts: ExternalCalendarConflictSource? = null,
) : RuntimeToolBinding {
    override suspend fun requestApproval(request: RuntimeToolCallRequest, context: RuntimeToolRouteContext): Boolean =
        throw ToolPolicyRejectedException("read-only tools do not request approval")

    override suspend fun executeReadOnly(request: RuntimeToolCallRequest, context: RuntimeToolRouteContext): RoutedToolResult {
        val args = parseToolArgs(
            request.argumentsJson,
            setOf("startAtEpochMs", "durationMinutes", "excludeScheduleId"),
        ) { ProviderFailure("INVALID_TOOL_ARGUMENTS", false) }
        val start = args["startAtEpochMs"]?.jsonPrimitive?.content?.toLongOrNull()
            ?: throw ProviderFailure("INVALID_TOOL_ARGUMENTS", false)
        val duration = args["durationMinutes"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: throw ProviderFailure("INVALID_TOOL_ARGUMENTS", false)
        if (start < 0 || duration !in 1..1440) throw ProviderFailure("INVALID_TOOL_ARGUMENTS", false)
        val end = Math.addExact(start, duration * 60_000L)
        val rows = schedules.findConflicts(start, end, args["excludeScheduleId"]?.jsonPrimitive?.content)
        val importedSourceIds = rows.mapNotNull { row ->
            row.id.takeIf { it.startsWith(SYSTEM_IMPORT_ID_PREFIX) }?.removePrefix(SYSTEM_IMPORT_ID_PREFIX)
        }.toSet()
        val externalRows = externalConflicts
            ?.findConflicts(start, end, args["excludeScheduleId"]?.jsonPrimitive?.content, 20)
            .orEmpty()
            .filterNot { it.sourceId in importedSourceIds }
        val safe = buildJsonObject {
            put("hasConflict", rows.isNotEmpty() || externalRows.isNotEmpty())
            put(
                "count",
                rows.size + externalRows.size,
            )
            put(
                "items",
                buildJsonArray {
                    rows.forEach { row ->
                        add(
                            buildJsonObject {
                                put("id", row.id)
                                put("title", row.title)
                                put("startAtEpochMs", row.startAtEpochMs)
                                put("durationMinutes", row.durationMinutes)
                                put("source", "ZHI_BAN")
                            },
                        )
                    }
                    externalRows.forEach { row ->
                        add(
                            buildJsonObject {
                                put("id", "$SYSTEM_IMPORT_ID_PREFIX${row.sourceId}")
                                put("title", row.title)
                                put("startAtEpochMs", row.startAtEpochMs)
                                put(
                                    "durationMinutes",
                                    ((row.endAtEpochMs - row.startAtEpochMs) / 60_000L).coerceAtLeast(1),
                                )
                                put("source", "SYSTEM_CALENDAR")
                            },
                        )
                    }
                },
            )
        }.toString()
        return RoutedToolResult(spec.name, request.providerCallId, safe)
    }

    private companion object {
        const val SYSTEM_IMPORT_ID_PREFIX = "system-calendar-"
    }
}
