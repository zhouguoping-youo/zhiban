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
class AgentControlStore internal constructor(context: Context, prefsName: String) {
    @Inject constructor(@ApplicationContext context: Context) : this(context, "agent_controls")

    // Tests pass an isolated prefsName so they never read or clobber the device's real "agent_controls".
    private val store = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
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

    // Native provider web search sends the query text to the model's search backend. Per product
    // decision it is enabled by default; the user can still turn it off here.
    fun webSearchOptIn(): Boolean = store.getBoolean("web_search_opt_in", true)

    fun saveWebSearchOptIn(enabled: Boolean) {
        check(store.edit().putBoolean("web_search_opt_in", enabled).commit())
    }

    // AI 回复建议：全局总开关（默认开）+ 按联系人"不再建议"。
    fun replySuggestionsEnabled(): Boolean = store.getBoolean("reply_suggestions_enabled", true)

    fun saveReplySuggestionsEnabled(enabled: Boolean) {
        check(store.edit().putBoolean("reply_suggestions_enabled", enabled).commit())
    }

    fun isReplyOptedOut(contactId: String): Boolean = contactId in (store.getStringSet("reply_opt_out_contacts", emptySet()) ?: emptySet())

    fun replyOptedOutContactIds(): Set<String> = store.getStringSet("reply_opt_out_contacts", emptySet()) ?: emptySet()

    fun setReplyOptOut(contactId: String, optedOut: Boolean) {
        val current = (store.getStringSet("reply_opt_out_contacts", emptySet()) ?: emptySet()).toMutableSet()
        if (optedOut) current.add(contactId) else current.remove(contactId)
        check(store.edit().putStringSet("reply_opt_out_contacts", current).commit())
    }

    fun isCompletionOptedOut(contactId: String): Boolean = contactId in (store.getStringSet("completion_opt_out_contacts", emptySet()) ?: emptySet())

    fun setCompletionOptOut(contactId: String, optedOut: Boolean) {
        val current = (store.getStringSet("completion_opt_out_contacts", emptySet()) ?: emptySet()).toMutableSet()
        if (optedOut) current.add(contactId) else current.remove(contactId)
        check(store.edit().putStringSet("completion_opt_out_contacts", current).commit())
    }

    // 联系人资料补全触达：全局总开关（默认开）+ 按联系人"不再打扰"（上文 isCompletionOptedOut/setCompletionOptOut）。
    fun contactCompletionEnabled(): Boolean = store.getBoolean("contact_completion_enabled", true)

    fun saveContactCompletionEnabled(enabled: Boolean) {
        check(store.edit().putBoolean("contact_completion_enabled", enabled).commit())
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
