package com.zhiban.rebuild.runtime.config

import android.content.Context
import com.zhiban.rebuild.data.config.ExecutionPreference
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class AgentDynamicConfig(
    val forceFtsOnly: Boolean = false,
    val llmTimeoutSeconds: Int = 30,
    val maxContextTokens: Int = 8_000,
    val disabledSkills: Set<String> = emptySet(),
    val providerBlacklist: Set<String> = emptySet(),
    val enableHybridRetrieval: Boolean = true,
    // Remote reranking is permitted because it uses the same mandatory outbound-policy adapter
    // as primary inference; SENSITIVE candidates are filtered before the request is constructed.
    val enableLlmRerank: Boolean = true,
    val enableAgentUndo: Boolean = true,
    val enableMcpRemote: Boolean = true,
    val executionPreference: ExecutionPreference = ExecutionPreference.BALANCED,
) {
    /** Recall ceiling merged across retrieval paths; 深度 recalls more candidates. */
    val retrievalRecallLimit: Int
        get() = if (executionPreference == ExecutionPreference.DEEP) DEEP_RECALL_LIMIT else DEFAULT_RECALL_LIMIT

    /** Effective config for the current execution preference. */
    fun withExecutionPreference(preference: ExecutionPreference): AgentDynamicConfig = when (preference) {
        // 快速：纯 FTS，跳过向量检索与 LLM 重排，少一次模型往返。
        ExecutionPreference.FAST -> copy(executionPreference = preference, forceFtsOnly = true, enableLlmRerank = false)

        // 深度：更大上下文窗口 + 更多检索召回。
        ExecutionPreference.DEEP -> copy(executionPreference = preference, maxContextTokens = deepMaxContextTokens(maxContextTokens))

        ExecutionPreference.BALANCED -> copy(executionPreference = preference)
    }

    companion object {
        const val DEFAULT_RECALL_LIMIT = 20
        const val DEEP_RECALL_LIMIT = 30

        internal fun deepMaxContextTokens(base: Int): Int = (base * 3 / 2).coerceIn(1_000, 128_000)
    }
}

/**
 * Three-layer Agent configuration: safe code defaults -> remotely supplied snapshot -> user override.
 * Remote transport is deliberately outside this class; only validated, non-secret values are accepted.
 */
@Singleton
class AgentDynamicConfigStore @Inject constructor(@ApplicationContext context: Context) {
    private val remote = context.getSharedPreferences("agent_remote_config", Context.MODE_PRIVATE)
    private val user = context.getSharedPreferences("agent_feature_overrides", Context.MODE_PRIVATE)

    fun snapshot(): AgentDynamicConfig {
        val defaults = AgentDynamicConfig()
        fun bool(key: String, fallback: Boolean) = if (user.contains(key)) user.getBoolean(key, fallback) else remote.getBoolean(key, fallback)
        return AgentDynamicConfig(
            forceFtsOnly = bool("force_fts_only", defaults.forceFtsOnly),
            llmTimeoutSeconds = remote.getInt("llm_timeout_seconds", defaults.llmTimeoutSeconds).coerceIn(5, 120),
            maxContextTokens = remote.getInt("max_context_tokens", defaults.maxContextTokens).coerceIn(1_000, 128_000),
            disabledSkills = remote.getStringSet("disable_skills", emptySet()).orEmpty().filter(::safeId).toSet(),
            providerBlacklist = remote.getStringSet(
                "provider_blacklist",
                emptySet(),
            ).orEmpty().filter(::safeId).toSet(),
            enableHybridRetrieval = bool("enable_hybrid_retrieval", defaults.enableHybridRetrieval),
            enableLlmRerank = bool("enable_llm_rerank", defaults.enableLlmRerank),
            enableAgentUndo = bool("enable_agent_undo", defaults.enableAgentUndo),
            enableMcpRemote = bool("enable_mcp_remote", defaults.enableMcpRemote),
        )
    }

    fun applyRemote(config: AgentDynamicConfig) {
        check(
            remote.edit()
                .putBoolean("force_fts_only", config.forceFtsOnly)
                .putInt("llm_timeout_seconds", config.llmTimeoutSeconds.coerceIn(5, 120))
                .putInt("max_context_tokens", config.maxContextTokens.coerceIn(1_000, 128_000))
                .putStringSet("disable_skills", config.disabledSkills.filter(::safeId).toSet())
                .putStringSet("provider_blacklist", config.providerBlacklist.filter(::safeId).toSet())
                .putBoolean("enable_hybrid_retrieval", config.enableHybridRetrieval)
                .putBoolean("enable_llm_rerank", config.enableLlmRerank)
                .putBoolean("enable_agent_undo", config.enableAgentUndo)
                .putBoolean("enable_mcp_remote", config.enableMcpRemote)
                .commit(),
        )
    }

    fun setUserOverride(key: String, enabled: Boolean?) {
        require(key in USER_BOOLEAN_KEYS)
        val edit = user.edit()
        if (enabled == null) edit.remove(key) else edit.putBoolean(key, enabled)
        check(edit.commit())
    }

    private fun safeId(value: String) = value.matches(Regex("[a-zA-Z0-9._-]{1,64}"))

    private companion object {
        val USER_BOOLEAN_KEYS =
            setOf("enable_hybrid_retrieval", "enable_llm_rerank", "enable_agent_undo", "enable_mcp_remote")
    }
}
