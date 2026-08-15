package com.zhiban.rebuild.runtime.tool

import com.zhiban.rebuild.data.communication.CommunicationHandoffLauncher
import com.zhiban.rebuild.runtime.provider.ProviderFailure
import com.zhiban.rebuild.runtime.runSuspendCatching
import com.zhiban.rebuild.runtime.store.ApprovedExternalToolReservationRequest
import com.zhiban.rebuild.runtime.store.ApprovedToolExecutionRequest
import com.zhiban.rebuild.runtime.store.RoomRuntimeStore
import com.zhiban.rebuild.runtime.store.abandonApprovedExternalToolReservation
import com.zhiban.rebuild.runtime.store.reserveApprovedExternalTool
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal class CommunicationMessageToolBinding(
    override val spec: RuntimeToolSpec,
    private val store: RoomRuntimeStore,
    private val handoffLauncher: CommunicationHandoffLauncher,
) : RuntimeToolBinding {
    override suspend fun requestApproval(request: RuntimeToolCallRequest, context: RuntimeToolRouteContext): Boolean {
        val arguments = parseArguments(request.argumentsJson)
        val platform = required(arguments, "platform").uppercase()
        val recipient = required(arguments, "recipient").take(MAX_RECIPIENT_CHARS)
        val message = required(arguments, "message").take(MAX_MESSAGE_CHARS)
        if (platform !in CommunicationHandoffLauncher.SUPPORTED_PLATFORMS) {
            throw ProviderFailure("INVALID_TOOL_ARGUMENTS", false)
        }
        val digest = communicationDigest(platform, recipient, message)
        val proposalId = "proposal-${sha256("${context.runId}:${request.providerCallId}").take(24)}"
        val payload = buildJsonObject {
            put("toolName", TOOL_NAME)
            put("providerCallId", request.providerCallId)
            put("logicalStepId", "step-${request.providerCallId}")
            put("proposalId", proposalId)
            put("payloadRef", "plan-${digest.take(32)}")
            put("revision", context.revision)
            put("canonicalInputDigest", digest)
            put("idempotencyKey", sha256("${context.runId}:${context.attemptId}:$digest"))
            put("platform", platform)
            put("recipient", recipient)
            put("message", message)
            put("title", "打开${platformLabel(platform)}发送消息")
        }.toString()
        return store.requestCommunicationApproval(
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
        require(planJson.toByteArray().size <= MAX_PLAN_BYTES) { "COMMUNICATION_PLAN_TOO_LARGE" }
        val plan = runSuspendCatching { Json.parseToJsonElement(planJson).jsonObject }
            .getOrElse { throw ProviderFailure("INVALID_TOOL_CALL", false) }
        require(plan.keys.all { it in PLAN_FIELDS }) { "COMMUNICATION_PLAN_UNKNOWN_FIELD" }
        fun value(name: String) = plan[name]?.jsonPrimitive?.content
            ?: throw ProviderFailure("INVALID_TOOL_CALL", false)
        require(value("toolName") == TOOL_NAME)
        val platform = value("platform")
        val recipient = value("recipient")
        val message = value("message")
        val expectedDigest = communicationDigest(platform, recipient, message)
        require(value("canonicalInputDigest") == expectedDigest) { "COMMUNICATION_PLAN_DIGEST_MISMATCH" }
        val idempotencyKey = value("idempotencyKey")
        store.toolResult(idempotencyKey)?.let { existing ->
            require(existing.canonicalInputDigest == expectedDigest) { "COMMUNICATION_IDEMPOTENCY_CONFLICT" }
            if (existing.status != "SUCCEEDED") {
                throw ProviderFailure("EXTERNAL_SIDE_EFFECT_OUTCOME_UNKNOWN", false)
            }
            return RoutedToolResult(TOOL_NAME, value("providerCallId"), requireNotNull(existing.safeResultJson))
        }
        val providerCallId = value("providerCallId")
        val reservation = store.reserveApprovedExternalTool(
            ApprovedExternalToolReservationRequest(
                context.runId,
                providerCallId,
                value("logicalStepId"),
                TOOL_NAME,
                spec.version,
                expectedDigest,
                idempotencyKey,
                context.ownerId,
                context.fencingEpoch,
                context.nowEpochMs,
            ),
        )
        if (!reservation.acquired) throw ProviderFailure("EXTERNAL_SIDE_EFFECT_OUTCOME_UNKNOWN", false)
        val handoff = runSuspendCatching { handoffLauncher.open(platform, recipient, message) }
            .getOrElse {
                store.abandonApprovedExternalToolReservation(reservation.execution.executionId, context.fencingEpoch)
                throw ProviderFailure("TARGET_APP_UNAVAILABLE", false)
            }
        val safeResult = buildJsonObject {
            put("platform", handoff.platform)
            put("recipient", recipient)
            put("status", handoff.status)
            put("requiresUserSend", handoff.requiresUserSend)
        }.toString()
        // The target app is already open. Committing the idempotency record must survive cancellation
        // (the 30s withTimeout in the router may fire right after startActivity); otherwise a recover
        // replay finds no record at the toolResult check above and launches the app a second time.
        withContext(NonCancellable) {
            store.completeApprovedRemoteTool(
                ApprovedToolExecutionRequest(
                    context.runId,
                    providerCallId,
                    value("logicalStepId"),
                    TOOL_NAME,
                    spec.version,
                    expectedDigest,
                    idempotencyKey,
                    safeResult,
                    context.ownerId,
                    context.fencingEpoch,
                    context.nowEpochMs,
                ),
            )
        }
        return RoutedToolResult(TOOL_NAME, providerCallId, safeResult)
    }

    private fun parseArguments(value: String) = runCatching { Json.parseToJsonElement(value).jsonObject }
        .getOrElse { throw ProviderFailure("INVALID_TOOL_ARGUMENTS", false) }

    private fun required(value: kotlinx.serialization.json.JsonObject, name: String): String =
        value[name]?.jsonPrimitive?.content?.trim()?.takeIf(String::isNotBlank)
            ?: throw ProviderFailure("INVALID_TOOL_ARGUMENTS", false)

    companion object {
        const val TOOL_NAME = "communication.message.compose"
        private const val MAX_RECIPIENT_CHARS = 120
        private const val MAX_MESSAGE_CHARS = 2_000
        private const val MAX_PLAN_BYTES = 16 * 1024
        private val PLAN_FIELDS = setOf(
            "toolName", "providerCallId", "logicalStepId", "proposalId", "payloadRef", "revision",
            "canonicalInputDigest", "idempotencyKey", "platform", "recipient", "message", "title",
        )

        internal fun communicationDigest(platform: String, recipient: String, message: String): String =
            sha256(listOf(platform, recipient, message).joinToString("|") { "${it.toByteArray().size}:$it" })

        internal fun platformLabel(platform: String): String = when (platform) {
            "SMS" -> "短信"
            "WECHAT" -> "微信"
            "QQ" -> "QQ"
            "TIM" -> "TIM"
            "FEISHU" -> "飞书"
            "LARK" -> "Lark"
            "WEWORK" -> "企业微信"
            "DINGTALK" -> "钉钉"
            else -> platform
        }
    }
}
