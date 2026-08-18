package com.zhiban.rebuild.runtime.tool

import com.zhiban.agent.memory.MemoryGate
import com.zhiban.agent.memory.MemoryQuery
import com.zhiban.rebuild.foundation.RuntimeToolSpec
import com.zhiban.rebuild.runtime.tool.RoomMemoryToolExecutor.Companion.GLOBAL_NAMESPACE
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal class MemoryDeleteToolBinding(override val spec: RuntimeToolSpec, private val store: com.zhiban.rebuild.runtime.store.RoomRuntimeStore) :
    RuntimeToolBinding {
    override suspend fun requestApproval(request: RuntimeToolCallRequest, context: RuntimeToolRouteContext): Boolean {
        val args = parseToolArgs(request.argumentsJson, setOf("logicalMemoryId"))
        val logicalMemoryId = args["logicalMemoryId"]?.jsonPrimitive?.content?.trim().orEmpty()
        require(logicalMemoryId.isNotBlank() && logicalMemoryId.length <= 128) { "INVALID_TOOL_ARGUMENTS" }
        val planEnvelope = PlanEnvelopeFactory.create(
            request,
            context,
            "memory.delete",
            PlanEnvelopeFactory.canonicalInputDigest(logicalMemoryId),
            payloadPrefix = "plan",
        )
        val plan = buildJsonObject {
            put("toolName", "memory.delete")
            put("providerCallId", request.providerCallId)
            put("logicalStepId", planEnvelope.logicalStepId)
            put("proposalId", planEnvelope.proposalId)
            put("payloadRef", planEnvelope.payloadRef)
            put("revision", context.revision)
            put("canonicalInputDigest", planEnvelope.canonicalInputDigest)
            put("idempotencyKey", planEnvelope.idempotencyKey)
            put("runId", context.runId)
            put("attemptId", context.attemptId)
            put("logicalMemoryId", logicalMemoryId)
            put("title", "删除这条长期记忆")
        }.toString()
        return store.requestMemoryDeleteApproval(
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
        val plan = Json.parseToJsonElement(planJson).jsonObject
        require(plan.keys.all { it in ALLOWED }) { "INVALID_MEMORY_DELETE_PLAN" }
        require(plan["toolName"]?.jsonPrimitive?.content == "memory.delete")
        val execution = store.completeApprovedMemoryDelete(
            plan,
            context.ownerId,
            context.fencingEpoch,
            context.nowEpochMs,
        )
        return RoutedToolResult(
            spec.name,
            plan.getValue("providerCallId").jsonPrimitive.content,
            requireNotNull(execution.safeResultJson),
        )
    }

    private companion object {
        val ALLOWED = setOf(
            "toolName", "providerCallId", "logicalStepId", "proposalId", "payloadRef", "revision",
            "canonicalInputDigest", "idempotencyKey", "runId", "attemptId", "logicalMemoryId", "title",
        )
    }
}

internal class MemorySearchToolBinding(override val spec: RuntimeToolSpec, private val search: MemoryGate) : RuntimeToolBinding {
    override suspend fun requestApproval(request: RuntimeToolCallRequest, context: RuntimeToolRouteContext) =
        throw ToolPolicyRejectedException("memory.search is read-only")

    override suspend fun executeReadOnly(request: RuntimeToolCallRequest, context: RuntimeToolRouteContext): RoutedToolResult {
        val args = parseToolArgs(request.argumentsJson, setOf("query", "limit"))
        val query = args["query"]?.jsonPrimitive?.content?.trim().orEmpty()
        val limit = (args["limit"]?.jsonPrimitive?.content?.toIntOrNull() ?: 10).coerceIn(1, 20)
        val result = search.search(
            MemoryQuery(GLOBAL_NAMESPACE, "local-user", "default", query, limit, tokenBudget = 2_000),
        )
        val safe = buildJsonObject {
            put("query", query)
            put("count", result.items.size)
            put("semanticSearchDegraded", result.semanticSearchDegraded)
            put("degradationReasons", buildJsonArray { result.degradationReasons.forEach(::add) })
            put(
                "memories",
                buildJsonArray {
                    result.items.forEach { item ->
                        add(
                            buildJsonObject {
                                put("memoryId", item.memoryId)
                                put("logicalMemoryId", item.logicalMemoryId)
                                put("content", item.canonicalText)
                                put("sensitivity", item.sensitivity)
                                put("score", item.score)
                                put("sourceRefs", buildJsonArray { item.sourceRefs.forEach(::add) })
                            },
                        )
                    }
                },
            )
        }.toString()
        return RoutedToolResult(spec.name, request.providerCallId, safe)
    }
}
