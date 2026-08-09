package com.zhiban.rebuild.runtime.provider

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

data class OutboundPolicyDecision(val request: ModelRequest, val redactedMessageCount: Int, val omittedMessageCount: Int, val blockedAttachmentCount: Int)

data class OutboundAuditEvent(
    val requestId: String,
    val channel: OutboundChannel,
    val purposes: Set<OutboundPurpose>,
    val sensitivities: Set<OutboundSensitivity>,
    val messageCount: Int,
    val attachmentCount: Int,
    val redactedMessageCount: Int,
    val omittedMessageCount: Int,
    val occurredAtEpochMs: Long,
    val outcome: OutboundAuditOutcome = OutboundAuditOutcome.EXPORT_ATTEMPTED,
    val byteCount: Long = 0,
)

enum class OutboundAuditOutcome { EXPORT_ATTEMPTED, BLOCKED_CONSENT, BLOCKED_POLICY }

fun interface OutboundAuditSink {
    suspend fun record(event: OutboundAuditEvent)
}

object NoOpOutboundAuditSink : OutboundAuditSink {
    override suspend fun record(event: OutboundAuditEvent) = Unit
}

fun interface OutboundDataPolicy {
    fun enforce(request: ModelRequest): OutboundPolicyDecision
}

data class OutboundPolicySettings(
    val allowRedactedAutomaticPersonalContext: Boolean = true,
    val allowCloudSpeech: Boolean = false,
    val allowRemoteMcp: Boolean = false,
    val allowRemoteEmbedding: Boolean = false,
)

data class OutboundExportDescriptor(
    val requestId: String,
    val channel: OutboundChannel,
    val purpose: OutboundPurpose,
    val sensitivities: Set<OutboundSensitivity>,
    val payloadCount: Int,
    val attachmentCount: Int = 0,
    val byteCount: Long = 0,
) {
    init {
        require(requestId.isNotBlank())
        require(sensitivities.isNotEmpty())
        require(payloadCount >= 0 && attachmentCount >= 0 && byteCount >= 0)
    }
}

enum class OutboundExportDecision { ALLOWED, CONSENT_REQUIRED, CONTENT_BLOCKED }

/**
 * Consent and metadata-audit choke point for non-LLM transports.
 *
 * The descriptor contains classifications and counts only. Payload text, audio bytes and tool
 * arguments are deliberately unavailable to the audit sink.
 */
class OutboundExportGate(
    private val settings: () -> OutboundPolicySettings,
    private val auditSink: OutboundAuditSink = NoOpOutboundAuditSink,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun consentGranted(channel: OutboundChannel): Boolean = when (channel) {
        OutboundChannel.LLM_INFERENCE,
        OutboundChannel.LLM_RERANK,
        -> true

        OutboundChannel.ASR_BATCH,
        OutboundChannel.ASR_REALTIME,
        -> settings().allowCloudSpeech

        OutboundChannel.MCP_REMOTE -> settings().allowRemoteMcp

        OutboundChannel.EMBEDDING -> settings().allowRemoteEmbedding
    }

    suspend fun evaluate(descriptor: OutboundExportDescriptor, contentAllowed: Boolean = true): OutboundExportDecision {
        val decision = when {
            !consentGranted(descriptor.channel) -> OutboundExportDecision.CONSENT_REQUIRED
            !contentAllowed -> OutboundExportDecision.CONTENT_BLOCKED
            else -> OutboundExportDecision.ALLOWED
        }
        auditSink.record(
            OutboundAuditEvent(
                requestId = descriptor.requestId,
                channel = descriptor.channel,
                purposes = setOf(descriptor.purpose),
                sensitivities = descriptor.sensitivities,
                messageCount = descriptor.payloadCount,
                attachmentCount = descriptor.attachmentCount,
                redactedMessageCount = 0,
                omittedMessageCount = if (decision == OutboundExportDecision.CONTENT_BLOCKED) {
                    descriptor.payloadCount
                } else {
                    0
                },
                occurredAtEpochMs = clock(),
                outcome = when (decision) {
                    OutboundExportDecision.ALLOWED -> OutboundAuditOutcome.EXPORT_ATTEMPTED
                    OutboundExportDecision.CONSENT_REQUIRED -> OutboundAuditOutcome.BLOCKED_CONSENT
                    OutboundExportDecision.CONTENT_BLOCKED -> OutboundAuditOutcome.BLOCKED_POLICY
                },
                byteCount = descriptor.byteCount,
            ),
        )
        return decision
    }
}

/**
 * Fail-closed policy for automatically supplied application data.
 *
 * User-authored text and user-selected attachments are deliberate send actions and remain intact.
 * Automatically retrieved PERSONAL text is stripped of direct identifiers. Automatically retrieved
 * SENSITIVE text is omitted. Regex detection is a final transport guard, not the source of truth.
 */
class DefaultOutboundDataPolicy(private val settings: () -> OutboundPolicySettings = { OutboundPolicySettings() }) : OutboundDataPolicy {
    override fun enforce(request: ModelRequest): OutboundPolicyDecision {
        val currentSettings = settings()
        var redacted = 0
        var omitted = 0
        val messages = request.messages.map { message ->
            require(message.role in ALLOWED_ROLES) { "OUTBOUND_ROLE_INVALID" }
            require(
                message.purpose != OutboundPurpose.SYSTEM_INSTRUCTION ||
                    message.sensitivity == OutboundSensitivity.PUBLIC,
            ) { "SYSTEM_INSTRUCTION_MUST_BE_PUBLIC" }

            val governed = when (message.purpose) {
                OutboundPurpose.USER_AUTHORED -> message.content

                OutboundPurpose.USER_SELECTED_ATTACHMENT -> error("ATTACHMENT_PURPOSE_INVALID_FOR_MESSAGE")

                OutboundPurpose.SYSTEM_INSTRUCTION -> message.content

                OutboundPurpose.AUTO_RETRIEVED,
                OutboundPurpose.TOOL_OBSERVATION,
                -> when (message.sensitivity) {
                    OutboundSensitivity.PUBLIC -> message.content

                    OutboundSensitivity.PERSONAL -> if (currentSettings.allowRedactedAutomaticPersonalContext) {
                        OutboundPiiRedactor.redact(
                            message.content,
                            redactStructuredPrivateFields = message.purpose == OutboundPurpose.TOOL_OBSERVATION,
                        )
                    } else {
                        OMITTED_PERSONAL_CONTENT
                    }

                    OutboundSensitivity.SENSITIVE -> OMITTED_SENSITIVE_CONTENT
                }
            }
            if (governed in OMITTED_CONTENT && governed != message.content) {
                omitted += 1
            } else if (governed != message.content) {
                redacted += 1
            }
            message.copy(content = governed)
        }

        val blockedAttachments = request.attachments.count { attachment ->
            attachment.purpose != OutboundPurpose.USER_SELECTED_ATTACHMENT &&
                attachment.sensitivity != OutboundSensitivity.PUBLIC
        }
        require(blockedAttachments == 0) { "AUTOMATIC_SENSITIVE_ATTACHMENT_BLOCKED" }
        request.attachments.forEach { attachment ->
            require(attachment.purpose == OutboundPurpose.USER_SELECTED_ATTACHMENT) {
                "ATTACHMENT_PURPOSE_INVALID"
            }
        }
        return OutboundPolicyDecision(
            request = request.copy(messages = messages),
            redactedMessageCount = redacted,
            omittedMessageCount = omitted,
            blockedAttachmentCount = blockedAttachments,
        )
    }

    private companion object {
        val ALLOWED_ROLES = setOf("system", "user", "assistant")
        const val OMITTED_SENSITIVE_CONTENT = "[已省略敏感内容]"
        const val OMITTED_PERSONAL_CONTENT = "[已关闭自动个人资料发送]"
        val OMITTED_CONTENT = setOf(OMITTED_SENSITIVE_CONTENT, OMITTED_PERSONAL_CONTENT)
    }
}

/** Prompt-safe redaction. Unlike diagnostic redaction, this never truncates the remaining text. */
internal object OutboundPiiRedactor {
    private val bearer = Regex("(?i)bearer\\s+[A-Za-z0-9._~+/=-]{8,}")
    private val credentialLike = Regex("(?i)(api[_-]?key|token|secret)\\s*[:=]\\s*[^\\s,;}]{4,}")
    private val mainlandPhone = Regex("(?<!\\d)(?:\\+?86[- ]?)?(1[3-9]\\d)[- ]?\\d{4}[- ]?(\\d{4})(?!\\d)")
    private val email =
        Regex("(?i)(?<![A-Z0-9._%+-])([A-Z0-9._%+-])([A-Z0-9._%+-]*)@([A-Z0-9.-]+\\.[A-Z]{2,})(?![A-Z0-9._%+-])")
    private val mainlandId = Regex("(?<![0-9A-Za-z])(\\d{6})\\d{8}(\\d{3}[0-9Xx])(?![0-9A-Za-z])")
    private val structuredPrivateField = Regex(
        "(?i)(\\\"(?:phone|email|wechatId|dingTalkId|feishuId|address|note)\\\"\\s*:\\s*\\\")([^\\\"]*)(\\\")",
    )

    fun redact(value: String, redactStructuredPrivateFields: Boolean = false): String {
        var safe = bearer.replace(value, "Bearer [REDACTED]")
        safe = credentialLike.replace(safe) { match ->
            match.value.substringBefore(':').substringBefore('=') + "=[REDACTED]"
        }
        safe = mainlandPhone.replace(safe) { match -> "${match.groupValues[1]}****${match.groupValues[2]}" }
        safe = email.replace(safe) { match -> "${match.groupValues[1]}***@${match.groupValues[3]}" }
        safe = mainlandId.replace(safe) { match -> "${match.groupValues[1]}********${match.groupValues[2]}" }
        if (redactStructuredPrivateFields) {
            safe = structuredPrivateField.replace(safe) { match ->
                "${match.groupValues[1]}[REDACTED]${match.groupValues[3]}"
            }
        }
        return safe
    }
}

/** Detection-only guard for channels where masked identifiers would create invalid semantics. */
object OutboundPiiDetector {
    private val bearer = Regex("(?i)bearer\\s+[A-Za-z0-9._~+/=-]{8,}")
    private val credentialLike = Regex("(?i)(api[_-]?key|token|secret)\\s*[:=]\\s*[^\\s,;}]{4,}")
    private val mainlandPhone = Regex("(?<!\\d)(?:\\+?86[- ]?)?1[3-9]\\d[- ]?\\d{4}[- ]?\\d{4}(?!\\d)")
    private val email = Regex("(?i)(?<![A-Z0-9._%+-])[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}(?![A-Z0-9._%+-])")
    private val mainlandId = Regex("(?<![0-9A-Za-z])\\d{6}\\d{8}\\d{3}[0-9Xx](?![0-9A-Za-z])")

    fun containsDirectIdentifier(value: String): Boolean = bearer.containsMatchIn(value) ||
        credentialLike.containsMatchIn(value) ||
        mainlandPhone.containsMatchIn(value) ||
        email.containsMatchIn(value) ||
        mainlandId.containsMatchIn(value)
}

/** Single model-transport choke point. New ProviderAdapter callers cannot bypass export policy. */
class PolicyEnforcingProviderAdapter(
    private val delegate: ProviderAdapter,
    private val policy: OutboundDataPolicy,
    private val auditSink: OutboundAuditSink = NoOpOutboundAuditSink,
    private val clock: () -> Long = System::currentTimeMillis,
) : ProviderAdapter {
    override suspend fun probe(profile: ProviderProfile): CapabilitySnapshot = delegate.probe(profile)

    override suspend fun probe(profile: ProviderProfile, requestId: String): CapabilitySnapshot = delegate.probe(profile, requestId)

    override fun stream(request: ModelRequest): Flow<ModelEvent> = flow {
        val decision = policy.enforce(request)
        val governed = decision.request
        auditSink.record(
            OutboundAuditEvent(
                requestId = governed.requestId,
                channel = governed.channel,
                purposes = governed.messages.mapTo(linkedSetOf(), ModelMessage::purpose) +
                    governed.attachments.map(ModelAttachment::purpose),
                sensitivities = governed.messages.mapTo(linkedSetOf(), ModelMessage::sensitivity) +
                    governed.attachments.map(ModelAttachment::sensitivity),
                messageCount = governed.messages.size,
                attachmentCount = governed.attachments.size,
                redactedMessageCount = decision.redactedMessageCount,
                omittedMessageCount = decision.omittedMessageCount,
                occurredAtEpochMs = clock(),
            ),
        )
        emitAll(delegate.stream(governed))
    }

    override fun cancel(requestId: String): Boolean = delegate.cancel(requestId)
}
