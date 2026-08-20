package com.zhiban.rebuild.data.config

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class MemoryPolicy(
    val sessionMemoryEnabled: Boolean = true,
    val longTermMemoryEnabled: Boolean = false,
    val learnFromConversations: Boolean = false,
    val temporaryModeEnabled: Boolean = false,
)
data class FeedbackPolicy(val useHumanFeedback: Boolean = true, val allowPreferenceImprovement: Boolean = true)
data class SilenceContactThresholds(val customerDays: Int = 30, val familyOrCloseFriendDays: Int = 14, val generalDays: Int = 60)
data class WakeupQuietHours(val enabled: Boolean = true, val startHour: Int = 23, val endHour: Int = 7)
data class WakeupThrottleState(val contactWakes: Map<String, Long> = emptyMap(), val globalWakes: List<Long> = emptyList())
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
        longTermMemoryEnabled = store.getBoolean("long_term_memory", false),
        learnFromConversations = store.getBoolean("learn_from_conversations", false),
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
            (policy.longTermMemoryEnabled && policy.learnFromConversations && !policy.temporaryModeEnabled)
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

    // Native provider web search sends the query text to an external search backend, so fresh
    // installs require an explicit opt-in before the capability can be exposed.
    fun webSearchOptIn(): Boolean = store.getBoolean("web_search_opt_in", false)

    fun saveWebSearchOptIn(enabled: Boolean) {
        check(store.edit().putBoolean("web_search_opt_in", enabled).commit())
    }

    fun silenceContactThresholds() = SilenceContactThresholds(
        customerDays = store.getInt("silence_customer_days", 30).coerceIn(1, 365),
        familyOrCloseFriendDays = store.getInt("silence_family_close_days", 14).coerceIn(1, 365),
        generalDays = store.getInt("silence_general_days", 60).coerceIn(1, 365),
    )

    fun saveSilenceContactThresholds(value: SilenceContactThresholds) {
        check(
            store.edit()
                .putInt("silence_customer_days", value.customerDays.coerceIn(1, 365))
                .putInt("silence_family_close_days", value.familyOrCloseFriendDays.coerceIn(1, 365))
                .putInt("silence_general_days", value.generalDays.coerceIn(1, 365))
                .commit(),
        )
    }

    fun unobservedReplyDays(): Int = store.getInt("unobserved_reply_days", 3).coerceIn(1, 30)

    fun saveUnobservedReplyDays(days: Int) {
        check(store.edit().putInt("unobserved_reply_days", days.coerceIn(1, 30)).commit())
    }

    fun wakeupQuietHours() = WakeupQuietHours(
        enabled = store.getBoolean("wakeup_quiet_enabled", true),
        startHour = store.getInt("wakeup_quiet_start", 23).coerceIn(0, 23),
        endHour = store.getInt("wakeup_quiet_end", 7).coerceIn(0, 23),
    )

    fun saveWakeupQuietHours(value: WakeupQuietHours) {
        check(
            store.edit()
                .putBoolean("wakeup_quiet_enabled", value.enabled)
                .putInt("wakeup_quiet_start", value.startHour.coerceIn(0, 23))
                .putInt("wakeup_quiet_end", value.endHour.coerceIn(0, 23))
                .commit(),
        )
    }

    fun wakeupThrottleState(): WakeupThrottleState = WakeupThrottleState(
        contactWakes = store.getStringSet("wakeup_contact_times", emptySet()).orEmpty().mapNotNull { encoded ->
            val split = encoded.indexOf('=')
            if (split <= 0) null else encoded.substring(0, split) to encoded.substring(split + 1).toLongOrNull()
        }.mapNotNull { (key, value) -> value?.let { key to it } }.toMap(),
        globalWakes = store.getStringSet("wakeup_global_times", emptySet()).orEmpty().mapNotNull(String::toLongOrNull).sorted(),
    )

    fun saveWakeupThrottleState(value: WakeupThrottleState) {
        check(
            store.edit()
                .putStringSet("wakeup_contact_times", value.contactWakes.mapTo(mutableSetOf()) { "${it.key}=${it.value}" })
                .putStringSet("wakeup_global_times", value.globalWakes.mapTo(mutableSetOf(), Long::toString))
                .commit(),
        )
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

    /** Experimental one-shot accessibility handoff; disabled until the user explicitly enables it. */
    fun smartForwardEnabled(): Boolean = store.getBoolean("smart_forward_enabled", false)

    fun saveSmartForwardEnabled(enabled: Boolean) {
        check(store.edit().putBoolean("smart_forward_enabled", enabled).commit())
    }

    fun smartForwardExplained(): Boolean = store.getBoolean("smart_forward_explained", false)

    fun markSmartForwardExplained() {
        check(store.edit().putBoolean("smart_forward_explained", true).commit())
    }

    // 定位读取：默认关（隐私优先——位置数据默认不出云）。开启后 location.current 才会把
    // 一次性坐标发给大模型；工具仍然只读、无后台轨迹。
    fun locationAccessEnabled(): Boolean = store.getBoolean("location_access_enabled", false)

    fun saveLocationAccessEnabled(enabled: Boolean) {
        check(store.edit().putBoolean("location_access_enabled", enabled).commit())
    }

    // 补全回复扫描游标:上次扫到的最后一条微信来消息时间。游标式分页扫描,防旧回复永久错过(P2-5)。
    fun completionScanCursor(): Long = store.getLong("completion_scan_cursor", 0L)

    fun saveCompletionScanCursor(cursorEpochMs: Long) {
        check(store.edit().putLong("completion_scan_cursor", cursorEpochMs).commit())
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
