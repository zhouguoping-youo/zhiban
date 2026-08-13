package com.zhiban.rebuild.runtime.tool

import com.zhiban.rebuild.data.notification.NotificationInsightAnalyzer
import com.zhiban.rebuild.runtime.provider.ProviderFailure
import com.zhiban.rebuild.runtime.store.RoomRuntimeStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal class ScheduleCreateToolBinding(
    override val spec: RuntimeToolSpec,
    private val store: RoomRuntimeStore,
    private val executor: RoomScheduleToolExecutor,
) : RuntimeToolBinding {
    override val aliases = setOf("calendar.create")

    override suspend fun requestApproval(request: RuntimeToolCallRequest, context: RuntimeToolRouteContext): Boolean {
        val call = validatedScheduleCall(request, context.runId, context.attemptId, context.revision)
        return store.requestScheduleApproval(
            call,
            context.sessionId,
            context.runId,
            context.attemptId,
            context.ownerId,
            context.fencingEpoch,
            context.nowEpochMs,
        )
    }

    override suspend fun executeApproved(planJson: String, context: ConfirmedToolExecutionContext): RoutedToolResult {
        val call = SchedulePlanValidator.validate(planJson)
        val result = executor.execute(
            context,
            call,
            ToolConfirmation(call.proposalId, call.payloadRef, call.revision, call.canonicalInputDigest),
        )
        return RoutedToolResult(spec.name, call.providerCallId, result.safeResultJson)
    }
}

internal class MemoryRememberToolBinding(
    override val spec: RuntimeToolSpec,
    private val store: RoomRuntimeStore,
    private val executor: RoomMemoryToolExecutor,
) : RuntimeToolBinding {
    override suspend fun requestApproval(request: RuntimeToolCallRequest, context: RuntimeToolRouteContext): Boolean {
        val call = validatedMemoryCall(request, context.runId, context.attemptId, context.revision)
        return store.requestMemoryApproval(
            call,
            context.sessionId,
            context.runId,
            context.attemptId,
            context.ownerId,
            context.fencingEpoch,
            context.nowEpochMs,
        )
    }

    override suspend fun executeApproved(planJson: String, context: ConfirmedToolExecutionContext): RoutedToolResult {
        val candidateId = Json.parseToJsonElement(planJson).jsonObject["candidateId"]?.jsonPrimitive?.content
            ?: throw ProviderFailure("INVALID_TOOL_CALL", false)
        val staged = store.stagedMemoryContent(candidateId, context.nowEpochMs)
            ?: throw ProviderFailure("INPUT_EXPIRED_OR_MISSING", false)
        val call = MemoryRememberPlanValidator.validate(planJson, staged)
        val result = executor.execute(
            context,
            call,
            ToolConfirmation(call.proposalId, call.payloadRef, call.revision, call.canonicalInputDigest),
        )
        return RoutedToolResult(spec.name, call.providerCallId, result.safeResultJson)
    }
}

internal fun validatedScheduleCall(request: RuntimeToolCallRequest, runId: String, attemptId: String, revision: Long): ScheduleCreateToolCall {
    val args = parseArguments(request.argumentsJson)
    fun required(name: String) = args[name]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)
        ?: throw ProviderFailure("INVALID_TOOL_ARGUMENTS", false)
    val sourceHint = args["sourceName"]?.jsonPrimitive?.content?.trim()
        ?: args["source"]?.jsonPrimitive?.content?.trim()
    val scheduleId = args["scheduleId"]?.jsonPrimitive?.content
        ?: "schedule-${sha256("$runId:${request.providerCallId}").take(24)}"
    val start = (args["startAtEpochMs"] ?: args["startTimeEpochMs"])?.jsonPrimitive?.content?.toLongOrNull()
        ?: throw ProviderFailure("INVALID_TOOL_ARGUMENTS", false)
    val rawTitle = required("title")
    val sanitizedTitle = NotificationInsightAnalyzer.sanitizeScheduleTitle(rawTitle, sourceHint).ifBlank {
        rawTitle
    }.trim()
    val proposalId = "proposal-${sha256("$runId:${request.providerCallId}").take(24)}"
    val payloadRef = "plan-${sha256(request.argumentsJson).take(32)}"
    val seed = ScheduleCreateToolCall(
        request.providerCallId, "step-${request.providerCallId}", proposalId, payloadRef, revision,
        "0".repeat(64), "pending", scheduleId, sanitizedTitle, start,
        args["durationMinutes"]?.jsonPrimitive?.content?.toIntOrNull() ?: 30,
        args["note"]?.jsonPrimitive?.content,
        args["reminderMinutesBefore"]?.jsonPrimitive?.content?.toIntOrNull(),
        args["crmActionId"]?.jsonPrimitive?.content,
    )
    val withDigest = seed.copy(canonicalInputDigest = canonicalScheduleDigest(seed))
    return withDigest.copy(idempotencyKey = canonicalToolIdempotencyKey(runId, attemptId, withDigest))
}

internal fun validatedMemoryCall(request: RuntimeToolCallRequest, runId: String, attemptId: String, revision: Long): MemoryRememberToolCall {
    val args = parseArguments(request.argumentsJson)
    fun required(name: String) = args[name]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)
        ?: throw ProviderFailure("INVALID_TOOL_ARGUMENTS", false)
    val content = required("content")
    val proposalId = "proposal-${sha256("$runId:${request.providerCallId}").take(24)}"
    val payloadRef = "plan-${sha256(request.argumentsJson).take(32)}"
    val seed = MemoryRememberToolCall(
        request.providerCallId, "step-${request.providerCallId}", proposalId, payloadRef, revision,
        "0".repeat(64), "pending",
        "candidate-${sha256("$runId:${request.providerCallId}:$content").take(24)}", content,
        args["memoryType"]?.jsonPrimitive?.content ?: "PREFERENCE",
        args["subjectKey"]?.jsonPrimitive?.content ?: "user",
        args["predicateKey"]?.jsonPrimitive?.content ?: "preference",
    )
    val withDigest = seed.copy(canonicalInputDigest = canonicalMemoryDigest(seed))
    return withDigest.copy(idempotencyKey = canonicalMemoryIdempotencyKey(runId, attemptId, withDigest))
}

private fun parseArguments(value: String) = runCatching { Json.parseToJsonElement(value).jsonObject }
    .getOrElse { throw ProviderFailure("INVALID_TOOL_ARGUMENTS", false) }
