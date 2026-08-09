package com.zhiban.rebuild.runtime.tool

import com.zhiban.rebuild.runtime.mcp.McpRemoteEnvironment
import com.zhiban.rebuild.runtime.mcp.McpRemoteTool
import com.zhiban.rebuild.runtime.provider.ProviderFailure
import com.zhiban.rebuild.runtime.runSuspendCatching
import com.zhiban.rebuild.runtime.store.RoomRuntimeStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** A discovered remote MCP tool enters CapabilityRouter at the same level as local bindings. */
internal class RemoteMcpToolBinding(private val remote: McpRemoteTool, private val environment: McpRemoteEnvironment, private val store: RoomRuntimeStore) :
    RuntimeToolBinding {
    override val spec = RuntimeToolSpec(
        name = remote.canonicalName,
        version = 1,
        // Remote tools are confirmation-gated by default, regardless of their description.
        risk = RuntimeToolRisk.WRITE_CONFIRMATION_REQUIRED,
        providerDefinitionJson = buildJsonObject {
            put("type", "function")
            put(
                "function",
                buildJsonObject {
                    put("name", remote.canonicalName)
                    put("description", remote.description?.take(1_000) ?: "外部 MCP 工具（执行前需要确认）")
                    put("parameters", remote.inputSchema)
                },
            )
        }.toString(),
        maxCallsPerRun = 4,
    )

    override suspend fun requestApproval(request: RuntimeToolCallRequest, context: RuntimeToolRouteContext): Boolean {
        require(request.argumentsJson.toByteArray().size <= MAX_ARGUMENT_BYTES) { "MCP_ARGUMENTS_TOO_LARGE" }
        val allowedArgumentKeys = remote.inputSchema["properties"]?.jsonObject?.keys
        val arguments = parseToolArgs(request.argumentsJson, allowedArgumentKeys) {
            ProviderFailure("INVALID_TOOL_ARGUMENTS", false)
        }
        val canonicalArguments = arguments.toString()
        val digest = sha256("${remote.serverId}:${remote.remoteName}:$canonicalArguments")
        val envelope = PlanEnvelopeFactory.create(request, context, spec.name, digest, "mcp-plan")
        val payload = buildJsonObject {
            put("toolName", spec.name)
            put("providerCallId", request.providerCallId)
            put("logicalStepId", envelope.logicalStepId)
            put("proposalId", envelope.proposalId)
            put("payloadRef", envelope.payloadRef)
            put("revision", context.revision)
            put("canonicalInputDigest", envelope.canonicalInputDigest)
            put("idempotencyKey", envelope.idempotencyKey)
            put("serverId", remote.serverId)
            put("remoteName", remote.remoteName)
            put("arguments", arguments)
            put("title", "允许外部服务执行：${remote.description?.take(40) ?: remote.remoteName}")
        }.toString()
        return store.requestRemoteMcpApproval(
            payload,
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
        require(planJson.toByteArray().size <= MAX_PLAN_BYTES) { "MCP_PLAN_TOO_LARGE" }
        val plan = runSuspendCatching { Json.parseToJsonElement(planJson).jsonObject }
            .getOrElse { throw ProviderFailure("INVALID_TOOL_CALL", false) }
        require(plan.keys.all { it in PLAN_FIELDS }) { "MCP_PLAN_UNKNOWN_FIELD" }
        require(plan.getValue("toolName").jsonPrimitive.content == spec.name)
        require(plan.getValue("serverId").jsonPrimitive.content == remote.serverId)
        require(plan.getValue("remoteName").jsonPrimitive.content == remote.remoteName)
        val arguments = plan.getValue("arguments").jsonObject
        val expected = sha256("${remote.serverId}:${remote.remoteName}:$arguments")
        require(plan.getValue("canonicalInputDigest").jsonPrimitive.content == expected) { "MCP_PLAN_DIGEST_MISMATCH" }
        val idempotencyKey = plan.getValue("idempotencyKey").jsonPrimitive.content
        store.toolResult(idempotencyKey)?.let { existing ->
            require(existing.canonicalInputDigest == expected) { "MCP_IDEMPOTENCY_CONFLICT" }
            return RoutedToolResult(
                spec.name,
                plan.getValue("providerCallId").jsonPrimitive.content,
                requireNotNull(existing.safeResultJson),
            )
        }
        val result = environment.call(remote.serverId, remote.remoteName, arguments)
        val validatedContent = validateRemoteMcpContent(result.content)
        val safeResult = buildJsonObject {
            put("sourceType", "REMOTE_MCP")
            put("serverId", remote.serverId)
            put("remoteTool", remote.remoteName)
            put("trust", "UNTRUSTED_EXTERNAL_DATA")
            put("disclosure", "内容来自已连接的外部 MCP 服务，未经知伴核验")
            put("content", validatedContent)
            put("isError", result.isError)
        }.toString()
        if (safeResult.toByteArray().size > MAX_RESULT_BYTES) throw ProviderFailure("MCP_RESULT_TOO_LARGE", false)
        val providerCallId = plan.getValue("providerCallId").jsonPrimitive.content
        store.completeApprovedRemoteTool(
            context.runId, providerCallId, plan.getValue("logicalStepId").jsonPrimitive.content,
            spec.name, spec.version, expected, idempotencyKey, safeResult,
            context.ownerId, context.fencingEpoch, context.nowEpochMs,
        )
        return RoutedToolResult(spec.name, providerCallId, safeResult)
    }

    companion object {
        private const val MAX_ARGUMENT_BYTES = 16 * 1024
        private const val MAX_PLAN_BYTES = 24 * 1024
        private const val MAX_RESULT_BYTES = 64 * 1024
        private val PLAN_FIELDS = setOf(
            "toolName", "providerCallId", "logicalStepId", "proposalId", "payloadRef", "revision",
            "canonicalInputDigest", "idempotencyKey", "serverId", "remoteName", "arguments", "title",
        )
    }
}

internal fun validateRemoteMcpContent(content: JsonArray): JsonArray {
    if (content.size > MAX_MCP_CONTENT_BLOCKS) throw ProviderFailure("MCP_RESULT_INVALID", false)
    content.forEach { element ->
        val block = element as? JsonObject ?: throw ProviderFailure("MCP_RESULT_INVALID", false)
        val type = (block["type"] as? JsonPrimitive)?.content
            ?: throw ProviderFailure("MCP_RESULT_INVALID", false)
        val allowedFields = MCP_CONTENT_FIELDS[type]
            ?: throw ProviderFailure("MCP_RESULT_UNSUPPORTED", false)
        if (block.keys.any { it !in allowedFields }) throw ProviderFailure("MCP_RESULT_INVALID", false)
        when (type) {
            "text" -> requireMcpPrimitive(block, "text")

            "image", "audio" -> {
                requireMcpPrimitive(block, "data")
                requireMcpPrimitive(block, "mimeType")
            }

            "resource" -> if (block["resource"] !is JsonObject) {
                throw ProviderFailure("MCP_RESULT_INVALID", false)
            }

            "resource_link" -> {
                requireMcpPrimitive(block, "name")
                requireMcpPrimitive(block, "uri")
            }
        }
    }
    return content
}

private fun requireMcpPrimitive(block: JsonObject, key: String) {
    if (block[key] !is JsonPrimitive) throw ProviderFailure("MCP_RESULT_INVALID", false)
}

private const val MAX_MCP_CONTENT_BLOCKS = 32
private val MCP_CONTENT_FIELDS = mapOf(
    "text" to setOf("type", "text", "annotations", "_meta"),
    "image" to setOf("type", "data", "mimeType", "annotations", "_meta"),
    "audio" to setOf("type", "data", "mimeType", "annotations", "_meta"),
    "resource" to setOf("type", "resource", "annotations", "_meta"),
    "resource_link" to setOf(
        "type", "name", "title", "uri", "description", "mimeType", "annotations", "size", "icons", "_meta",
    ),
)
