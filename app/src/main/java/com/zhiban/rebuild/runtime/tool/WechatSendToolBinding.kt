package com.zhiban.rebuild.runtime.tool

import com.zhiban.rebuild.data.ilink.ContactWechatResolver
import com.zhiban.rebuild.data.ilink.IlinkMessageSender
import com.zhiban.rebuild.data.ilink.WechatRecipientResolution
import com.zhiban.rebuild.data.ilink.network.IlinkSessionExpiredException
import com.zhiban.rebuild.runtime.provider.ProviderFailure
import com.zhiban.rebuild.runtime.runSuspendCatching
import com.zhiban.rebuild.runtime.store.RoomRuntimeStore
import com.zhiban.rebuild.runtime.store.requestWechatSendApproval
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Governance binding for `communication.wechat.send`: a real WeChat message delivered through the
 * iLink Bot API after the user confirms on the card. Unlike `communication.message.compose` (which
 * only opens the target app), this sends for real and cannot be undone, so the card carries an
 * explicit "不可撤销" warning and every send is idempotency-guarded.
 *
 * The recipient is resolved to an iLink `userId` at approval time; if the contact is unknown or has
 * no learned `userId` yet, the call fails with a typed code so the Agent falls back to
 * `communication.message.compose` instead of asking the user to confirm an unsendable message.
 */
internal class WechatSendToolBinding(
    override val spec: RuntimeToolSpec,
    private val store: RoomRuntimeStore,
    private val resolver: ContactWechatResolver,
    private val sender: IlinkMessageSender,
) : RuntimeToolBinding {

    override suspend fun requestApproval(request: RuntimeToolCallRequest, context: RuntimeToolRouteContext): Boolean {
        val arguments = parseArguments(request.argumentsJson)
        val recipient = required(arguments, "recipient").take(MAX_RECIPIENT_CHARS)
        val message = required(arguments, "message").take(MAX_MESSAGE_CHARS)
        val resolved = when (val resolution = resolver.resolveUserId(recipient)) {
            is WechatRecipientResolution.Resolved -> resolution

            is WechatRecipientResolution.ContactNotFound ->
                throw ProviderFailure("ILINK_CONTACT_NOT_FOUND", false)

            is WechatRecipientResolution.NoWechatLink ->
                throw ProviderFailure("ILINK_RECIPIENT_NOT_LINKED", false)
        }
        val displayName = resolved.contact.displayName.take(MAX_RECIPIENT_CHARS)
        val digest = wechatSendDigest(displayName, message)
        val envelope = PlanEnvelopeFactory.create(request, context, TOOL_NAME, digest)
        val payload = buildJsonObject {
            put("toolName", TOOL_NAME)
            put("providerCallId", request.providerCallId)
            put("logicalStepId", envelope.logicalStepId)
            put("proposalId", envelope.proposalId)
            put("payloadRef", envelope.payloadRef)
            put("revision", context.revision)
            put("canonicalInputDigest", envelope.canonicalInputDigest)
            put("idempotencyKey", envelope.idempotencyKey)
            put("platform", PLATFORM_VALUE)
            put("recipient", displayName)
            put("message", message)
            put("userId", resolved.userId)
            put("title", "发送微信给 $displayName")
        }.toString()
        return store.requestWechatSendApproval(
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
        require(planJson.toByteArray().size <= MAX_PLAN_BYTES) { "WECHAT_SEND_PLAN_TOO_LARGE" }
        val plan = runSuspendCatching { Json.parseToJsonElement(planJson).jsonObject }
            .getOrElse { throw ProviderFailure("INVALID_TOOL_CALL", false) }
        require(plan.keys.all { it in PLAN_FIELDS }) { "WECHAT_SEND_PLAN_UNKNOWN_FIELD" }
        fun value(name: String) = plan[name]?.jsonPrimitive?.content
            ?: throw ProviderFailure("INVALID_TOOL_CALL", false)
        require(value("toolName") == TOOL_NAME)
        val recipient = value("recipient")
        val message = value("message")
        val userId = value("userId")
        val expectedDigest = wechatSendDigest(recipient, message)
        require(value("canonicalInputDigest") == expectedDigest) { "WECHAT_SEND_PLAN_DIGEST_MISMATCH" }
        val idempotencyKey = value("idempotencyKey")
        store.toolResult(idempotencyKey)?.let { existing ->
            require(existing.canonicalInputDigest == expectedDigest) { "WECHAT_SEND_IDEMPOTENCY_CONFLICT" }
            if (existing.status != "SUCCEEDED") {
                throw ProviderFailure("EXTERNAL_SIDE_EFFECT_OUTCOME_UNKNOWN", false)
            }
            return RoutedToolResult(TOOL_NAME, value("providerCallId"), requireNotNull(existing.safeResultJson))
        }
        return store.executeApprovedExternalTool(
            context = context,
            spec = spec,
            toolName = TOOL_NAME,
            providerCallId = value("providerCallId"),
            logicalStepId = value("logicalStepId"),
            expectedDigest = expectedDigest,
            idempotencyKey = idempotencyKey,
            onFailure = { toSendFailure(it) },
        ) {
            val sent = sender.sendText(userId, message, stableClientId(idempotencyKey))
            buildJsonObject {
                put("status", "sent")
                put("recipient", recipient)
                put("messageId", sent.messageId ?: -1L)
                put("threadedIntoConversation", sent.threadedIntoConversation)
            }.toString()
        }
    }

    private fun parseArguments(value: String) = runCatching { Json.parseToJsonElement(value).jsonObject }
        .getOrElse { throw ProviderFailure("INVALID_TOOL_ARGUMENTS", false) }

    private fun required(value: kotlinx.serialization.json.JsonObject, name: String): String =
        value[name]?.jsonPrimitive?.content?.trim()?.takeIf(String::isNotBlank)
            ?: throw ProviderFailure("INVALID_TOOL_ARGUMENTS", false)

    private fun toSendFailure(failure: Throwable): ProviderFailure = when (failure) {
        is ProviderFailure -> failure
        is IlinkSessionExpiredException -> ProviderFailure("ILINK_SESSION_EXPIRED", false)
        else -> ProviderFailure("ILINK_SEND_FAILED", false)
    }

    companion object {
        const val TOOL_NAME = "communication.wechat.send"

        /** Distinct platform discriminator so the confirmation card warns "真实发送" instead of "打开目标应用". */
        const val PLATFORM_VALUE = "WECHAT_ILINK"
        private const val MAX_RECIPIENT_CHARS = 120
        private const val MAX_MESSAGE_CHARS = 2_000
        private const val MAX_PLAN_BYTES = 16 * 1024
        private const val CLIENT_ID_HEX_LENGTH = 16
        private val PLAN_FIELDS = setOf(
            "toolName", "providerCallId", "logicalStepId", "proposalId", "payloadRef", "revision",
            "canonicalInputDigest", "idempotencyKey", "platform", "recipient", "message", "userId", "title",
        )

        internal fun wechatSendDigest(recipient: String, message: String): String =
            sha256(listOf(recipient, message).joinToString("|") { "${it.toByteArray().size}:$it" })

        /** Deterministic per-idempotency-key client id, so retries dedup server-side. */
        internal fun stableClientId(idempotencyKey: String): String = sha256(idempotencyKey).take(CLIENT_ID_HEX_LENGTH)
    }
}
