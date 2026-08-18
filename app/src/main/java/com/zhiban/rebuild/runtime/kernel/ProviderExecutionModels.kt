package com.zhiban.rebuild.runtime.kernel

import com.zhiban.agent.skills.SkillActivation
import com.zhiban.rebuild.provider.CapabilitySnapshot
import com.zhiban.rebuild.provider.ModelEvent
import com.zhiban.rebuild.provider.ModelRequest
import com.zhiban.rebuild.provider.ProviderProfile
import com.zhiban.rebuild.runtime.config.AgentDynamicConfig
import com.zhiban.rebuild.runtime.context.ContextRetrievalResult
import com.zhiban.rebuild.runtime.context.QueryContext
import com.zhiban.rebuild.runtime.network.NetworkQuality
import com.zhiban.rebuild.runtime.store.SessionConversationContext

internal sealed class PreparedRun {
    data class Failure(val code: String, val retryable: Boolean) : PreparedRun()

    data class LocalCalendarTool(
        val input: DecodedInput,
        val queryContext: QueryContext,
        val activatedSkills: List<SkillActivation>,
        val attemptId: String,
        val toolCall: ModelEvent.ToolCall,
        val forcedCanonicalTool: String?,
    ) : PreparedRun()

    data class Ready(
        val input: DecodedInput,
        val queryContext: QueryContext,
        val retrieval: ContextRetrievalResult,
        val approvedMemories: List<String>,
        val conversationContext: SessionConversationContext,
        val feedback: List<String>,
        val activatedSkills: List<SkillActivation>,
        val profile: ProviderProfile,
        val config: AgentDynamicConfig,
        val currentNetwork: NetworkQuality,
        val attemptId: String,
    ) : PreparedRun()
}

internal data class RunIdentifiers(val runId: String, val sessionId: String, val fencingEpoch: Long)

internal data class ToolCallContext(
    val input: DecodedInput,
    val queryContext: QueryContext,
    val activatedSkills: List<SkillActivation>,
    val attemptId: String,
    val providerRequestActive: Boolean,
)

internal data class AssembledReActContext(
    val capability: CapabilitySnapshot,
    val retrieval: ContextRetrievalResult,
    val assembledMessages: AssembledModelContext,
)

internal data class PreparedReActRequest(val request: ModelRequest, val forcedCanonicalTool: String?, val capability: CapabilitySnapshot)

internal data class ObservationSetup(
    val input: DecodedInput,
    val queryContext: QueryContext,
    val retrieval: ContextRetrievalResult,
    val approvedMemories: List<String>,
    val conversationContext: SessionConversationContext,
    val feedback: List<String>,
    val activatedSkills: List<SkillActivation>,
    val profile: ProviderProfile,
    val config: AgentDynamicConfig,
    val attemptId: String,
)

internal data class ObservationProbeResult(
    val capability: CapabilitySnapshot,
    val retrieval: ContextRetrievalResult,
    val observation: String,
    val observationTools: Set<String>,
)

internal data class PerceptionResult(val context: QueryContext, val durationMs: Long, val degraded: Boolean)

internal data class MemoryContext(
    val retrieval: ContextRetrievalResult,
    val approvedMemories: List<String>,
    val conversation: SessionConversationContext,
    val feedback: List<String>,
)
