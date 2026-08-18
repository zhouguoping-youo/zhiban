package com.zhiban.rebuild.runtime.context

import com.zhiban.rebuild.foundation.Sensitivity

enum class ContextLayer { STABLE, CONTEXT, VOLATILE }
enum class TrustLevel { SYSTEM, TRUSTED_APP, UNTRUSTED_MODEL, UNTRUSTED_TOOL, UNTRUSTED_MEMORY }
enum class ContextKind { TEXT, TOOL_CALL, TOOL_RESULT, SUMMARY }

data class ContextProvenance(
    val sourceType: String,
    val sourceId: String,
    val sourceSequence: Long,
    val producerVersion: String,
    val digest: String,
    val schemaVersion: Int = 1,
)

data class ContextBlock(
    val id: String,
    val layer: ContextLayer,
    val content: String,
    val trust: TrustLevel,
    val sensitivity: Sensitivity,
    val tokenCost: Int,
    val provenance: ContextProvenance,
    val atomicGroupId: String? = null,
    val kind: ContextKind = ContextKind.TEXT,
    val isRequired: Boolean = false,
) {
    init {
        require(id.isNotBlank() && content.toByteArray().size <= 64 * 1024)
        require(tokenCost >= 0)
        if (kind == ContextKind.TOOL_CALL || kind == ContextKind.TOOL_RESULT) require(!atomicGroupId.isNullOrBlank())
    }
}

data class PromptBudget(val maxTokens: Int, val reservedOutputTokens: Int) {
    val availableInputTokens = maxTokens - reservedOutputTokens
    init {
        require(maxTokens > 0 && reservedOutputTokens >= 0 && availableInputTokens >= 0)
    }
}

enum class PromptMessageRole { SYSTEM, DATA }
data class PromptMessage(
    val schemaVersion: Int = 1,
    val role: PromptMessageRole,
    val content: String,
    val trust: TrustLevel,
    val sensitivity: Sensitivity,
    val provenance: ContextProvenance,
)
data class PromptAssembly(val included: List<ContextBlock>, val omittedIds: List<String>, val usedTokens: Int, val messages: List<PromptMessage>)
