package com.zhiban.rebuild.runtime.config

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class MemoryPolicy(
    val sessionMemoryEnabled: Boolean = true,
    val longTermMemoryEnabled: Boolean = true,
    val learnFromConversations: Boolean = true,
    val temporaryModeEnabled: Boolean = false,
)
data class FeedbackPolicy(val useHumanFeedback: Boolean = true, val allowPreferenceImprovement: Boolean = true)
data class PreferenceImprovementSuggestion(val id: String, val title: String, val description: String)
enum class ExecutionPreference(val label: String, val runtimeLevel: String) {
    FAST("快速", "快速"),
    BALANCED("平衡", "标准"),
    DEEP("深度", "深入"),
}

@Singleton
class AgentControlStore @Inject constructor(@ApplicationContext context: Context) {
    private val store = context.getSharedPreferences("agent_controls", Context.MODE_PRIVATE)
    fun memory() = MemoryPolicy(
        sessionMemoryEnabled = store.getBoolean("session_memory", true),
        longTermMemoryEnabled = store.getBoolean("long_term_memory", true),
        learnFromConversations = store.getBoolean("learn_from_conversations", true),
        temporaryModeEnabled = store.getBoolean("temporary_memory_mode", false),
    )
    fun saveMemory(value: MemoryPolicy) {
        check(
            store.edit()
                .putBoolean("session_memory", value.sessionMemoryEnabled)
                .putBoolean("long_term_memory", value.longTermMemoryEnabled)
                .putBoolean("learn_from_conversations", value.learnFromConversations)
                .putBoolean("temporary_memory_mode", value.temporaryModeEnabled)
                .commit(),
        )
    }
    fun isToolAvailable(name: String): Boolean {
        if (!isToolEnabled(name)) return false
        val policy = memory()
        return name !in setOf("memory.remember", "memory.upsert") ||
            (policy.learnFromConversations && !policy.temporaryModeEnabled)
    }
    fun feedback() = FeedbackPolicy(store.getBoolean("human_feedback", true), store.getBoolean("preference_improvement", true))
    fun saveFeedback(value: FeedbackPolicy) {
        check(
            store.edit().putBoolean(
                "human_feedback",
                value.useHumanFeedback,
            ).putBoolean("preference_improvement", value.allowPreferenceImprovement).commit(),
        )
    }
    fun execution() = runCatching {
        ExecutionPreference.valueOf(store.getString("execution_preference", ExecutionPreference.BALANCED.name)!!)
    }.getOrDefault(ExecutionPreference.BALANCED)
    fun saveExecution(value: ExecutionPreference) {
        check(store.edit().putString("execution_preference", value.name).commit())
    }
    fun isToolEnabled(name: String): Boolean = name !in (store.getStringSet("disabled_tools", emptySet()) ?: emptySet())
    fun saveToolEnabled(name: String, enabled: Boolean) {
        val disabled = (store.getStringSet("disabled_tools", emptySet()) ?: emptySet()).toMutableSet()
        if (enabled) disabled.remove(name) else disabled.add(name)
        check(store.edit().putStringSet("disabled_tools", disabled).commit())
    }
    fun isSkillEnabled(id: String): Boolean = id !in (store.getStringSet("disabled_skills", emptySet()) ?: emptySet())
    fun saveSkillEnabled(id: String, enabled: Boolean) {
        val disabled = (store.getStringSet("disabled_skills", emptySet()) ?: emptySet()).toMutableSet()
        if (enabled) disabled.remove(id) else disabled.add(id)
        check(store.edit().putStringSet("disabled_skills", disabled).commit())
    }
    fun recordHumanFeedback(positive: Boolean) {
        val key = if (positive) "positive_feedback_count" else "negative_feedback_count"
        val count = store.getInt(key, 0) + 1
        val edit = store.edit().putInt(key, count)
        if (!positive && feedback().allowPreferenceImprovement && count >= 3 &&
            store.getString("pending_improvement", null) == null
        ) {
            edit.putString("pending_improvement", "concise_after_negative_feedback")
        }
        check(edit.commit())
    }
    fun pendingImprovement(): PreferenceImprovementSuggestion? = when (store.getString("pending_improvement", null)) {
        "concise_after_negative_feedback" -> PreferenceImprovementSuggestion(
            "concise_after_negative_feedback",
            "尝试更简洁的回答风格",
            "根据连续的改进反馈，建议先给结论，再补充必要说明。接受后才会修改个性化设置。",
        )

        else -> null
    }
    fun dismissImprovement() {
        check(store.edit().remove("pending_improvement").putInt("negative_feedback_count", 0).commit())
    }
}
