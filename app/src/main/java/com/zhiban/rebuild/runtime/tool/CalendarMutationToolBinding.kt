package com.zhiban.rebuild.runtime.tool

import com.zhiban.rebuild.foundation.RuntimeToolSpec
import com.zhiban.rebuild.provider.ProviderFailure
import com.zhiban.rebuild.runtime.store.RoomRuntimeStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal class CalendarMutationToolBinding(override val spec: RuntimeToolSpec, private val store: RoomRuntimeStore) : RuntimeToolBinding {
    override suspend fun requestApproval(request: RuntimeToolCallRequest, context: RuntimeToolRouteContext): Boolean {
        val plan = buildPlan(request, context)
        return store.requestCalendarMutationApproval(
            plan,
            request.providerCallId,
            context.sessionId,
            context.runId,
            context.attemptId,
            context.ownerId,
            context.fencingEpoch,
            context.nowEpochMs,
        )
    }

    override suspend fun executeApproved(planJson: String, context: ConfirmedToolExecutionContext): RoutedToolResult {
        val plan = parseAndValidate(planJson)
        val result = store.completeApprovedCalendarMutation(
            plan,
            context.ownerId,
            context.fencingEpoch,
            context.nowEpochMs,
        )
        return RoutedToolResult(
            spec.name,
            plan.getValue("providerCallId").jsonPrimitive.content,
            requireNotNull(result.safeResultJson),
        )
    }

    private fun buildPlan(request: RuntimeToolCallRequest, context: RuntimeToolRouteContext): String {
        val args = parseToolArgs(request.argumentsJson, null) { throw ProviderFailure("INVALID_TOOL_ARGUMENTS", false) }
        val allowed = if (spec.name ==
            UPDATE
        ) {
            setOf("scheduleId", "title", "startAtEpochMs", "durationMinutes", "note")
        } else {
            setOf("scheduleId")
        }
        if (args.keys.any { it !in allowed }) throw ProviderFailure("INVALID_TOOL_ARGUMENTS", false)
        fun required(name: String) = args[name]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)
            ?: throw ProviderFailure("INVALID_TOOL_ARGUMENTS", false)
        val scheduleId = required("scheduleId")
        val base = buildJsonObject {
            put("toolName", spec.name)
            put("providerCallId", request.providerCallId)
            put("runId", context.runId)
            put("attemptId", context.attemptId)
            put("logicalStepId", "step-${request.providerCallId}")
            put("revision", context.revision)
            put("scheduleId", scheduleId)
            if (spec.name == UPDATE) {
                put("title", required("title").take(200))
                val start = args["startAtEpochMs"]?.jsonPrimitive?.content?.toLongOrNull()
                    ?: throw ProviderFailure("INVALID_TOOL_ARGUMENTS", false)
                val duration = args["durationMinutes"]?.jsonPrimitive?.content?.toIntOrNull()
                    ?: throw ProviderFailure("INVALID_TOOL_ARGUMENTS", false)
                if (start < 0 || duration !in 1..1440) throw ProviderFailure("INVALID_TOOL_ARGUMENTS", false)
                put("startAtEpochMs", start)
                put("durationMinutes", duration)
                args["note"]?.jsonPrimitive?.content?.take(2_000)?.let { put("note", it) }
            } else {
                put("title", "删除日程")
            }
        }
        val digest = sha256(base.toString())
        val envelope = PlanEnvelopeFactory.create(request, context, spec.name, digest, "calendar-plan")
        return JsonObject(
            base + mapOf(
                "canonicalInputDigest" to kotlinx.serialization.json.JsonPrimitive(digest),
                "idempotencyKey" to
                    kotlinx.serialization.json.JsonPrimitive(envelope.idempotencyKey),
                "proposalId" to kotlinx.serialization.json.JsonPrimitive(envelope.proposalId),
                "payloadRef" to kotlinx.serialization.json.JsonPrimitive(envelope.payloadRef),
                "titleForApproval" to
                    kotlinx.serialization.json.JsonPrimitive(if (spec.name == UPDATE) "修改日程" else "删除日程"),
            ),
        ).toString()
    }

    private fun parseAndValidate(value: String): JsonObject {
        require(value.toByteArray().size <= 16 * 1024) { "CALENDAR_PLAN_TOO_LARGE" }
        val plan = Json.parseToJsonElement(value).jsonObject
        require(plan["toolName"]?.jsonPrimitive?.content == spec.name)
        val stripped = JsonObject(
            plan.filterKeys {
                it !in
                    setOf("canonicalInputDigest", "idempotencyKey", "titleForApproval", "proposalId", "payloadRef")
            },
        )
        require(plan["canonicalInputDigest"]?.jsonPrimitive?.content == sha256(stripped.toString()))
        return plan
    }

    companion object {
        const val UPDATE = "calendar.schedule.update"
        const val DELETE = "calendar.schedule.delete"
    }
}
