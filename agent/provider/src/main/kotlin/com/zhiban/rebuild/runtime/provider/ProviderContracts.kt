package com.zhiban.rebuild.runtime.provider

import kotlinx.coroutines.flow.Flow

data class ProviderProfile(val providerId: String, val endpointId: String, val modelId: String, val credentialRef: String, val keyVersion: Int)

data class CapabilitySnapshot(
    val profileDigest: String,
    val modalities: Set<String>,
    val features: Set<String>,
    val maxContextTokens: Int,
    val maxOutputTokens: Int,
    val observedAtEpochMs: Long,
    val expiresAtEpochMs: Long,
) {
    fun requireFresh(nowEpochMs: Long, expectedProfileDigest: String) {
        check(profileDigest == expectedProfileDigest) { "CAPABILITY_PROFILE_MISMATCH" }
        check(expiresAtEpochMs > nowEpochMs) { "CAPABILITY_EXPIRED" }
    }
}

/** External transport selected for an outbound request. */
enum class OutboundChannel {
    LLM_INFERENCE,
    LLM_RERANK,
    ASR_BATCH,
    ASR_REALTIME,
    MCP_REMOTE,
    EMBEDDING,
    WECHAT_ILINK,
}

/** Export classification. Callers must classify every outbound value explicitly. */
enum class OutboundSensitivity { PUBLIC, PERSONAL, SENSITIVE }

/** Why this value is leaving the device; policy differs for user-authored and automatic data. */
enum class OutboundPurpose {
    SYSTEM_INSTRUCTION,
    USER_AUTHORED,
    AUTO_RETRIEVED,
    TOOL_OBSERVATION,
    USER_SELECTED_ATTACHMENT,
}

/** Non-content source metadata retained for policy and audit decisions. */
data class OutboundProvenance(val sourceType: String, val sourceId: String) {
    init {
        require(sourceType.matches(Regex("[A-Za-z0-9._-]{1,80}")))
        require(sourceId.matches(Regex("[A-Za-z0-9._:-]{1,160}")))
    }
}

data class ModelMessage(
    val role: String,
    val content: String,
    val sensitivity: OutboundSensitivity,
    val purpose: OutboundPurpose,
    val provenance: OutboundProvenance,
)

data class ModelAttachment(
    val attachmentId: String,
    val kind: String,
    val mimeType: String,
    val byteLength: Long,
    val sha256Digest: String,
    val contentRef: String,
    val expiresAtEpochMs: Long,
    val sensitivity: OutboundSensitivity,
    val purpose: OutboundPurpose,
    val provenance: OutboundProvenance,
)
data class ModelRequest(
    val requestId: String,
    val channel: OutboundChannel,
    val profile: ProviderProfile,
    val messages: List<ModelMessage>,
    val capability: CapabilitySnapshot,
    val maxTokens: Int,
    val jsonSchema: String? = null,
    /** Provider-neutral OpenAI-compatible tool definitions. Null means plain chat. */
    val toolsJson: String? = null,
    /** Allow the trusted provider's built-in public web search for the primary answer. */
    val allowWebSearch: Boolean = false,
    val attachments: List<ModelAttachment> = emptyList(),
    /** Provider-facing function name to require for an explicit high-confidence write intent. */
    val forcedToolName: String? = null,
)

sealed interface ModelEvent {
    data class Delta(val ordinal: Long, val text: String) : ModelEvent
    data class ToolCall(val ordinal: Long, val providerCallId: String, val name: String, val argumentsJson: String) : ModelEvent
    data class Usage(val inputTokens: Int, val outputTokens: Int) : ModelEvent
    data class Final(val finishReason: String) : ModelEvent
}

data class ProviderFailure(val code: String, val retryable: Boolean, val retryAfterMillis: Long? = null, val safeRequestId: String? = null) :
    RuntimeException(code)

interface ProviderAdapter {
    suspend fun probe(profile: ProviderProfile): CapabilitySnapshot
    suspend fun probe(profile: ProviderProfile, requestId: String): CapabilitySnapshot = probe(profile)
    fun stream(request: ModelRequest): Flow<ModelEvent>
    fun cancel(requestId: String): Boolean
}

interface CredentialResolver {
    suspend fun <T> withCredential(credentialRef: String, keyVersion: Int, block: suspend (ByteArray) -> T): T
}
