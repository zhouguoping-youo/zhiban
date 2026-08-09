package com.zhiban.rebuild.runtime.personalization

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class Personalization(val preferredName: String = "", val style: ResponseStyle = ResponseStyle.BALANCED)

/**
 * 回答风格。label 用于界面展示，promptFragment 注入 system message。
 * 枚举只增不改序：旧 SharedPreferences 存储的 name 仍可解析（见 [AgentPersonalizationStore.load]）。
 * CUSTOM 没有独立 prompt 片段——选中时使用用户在个人资料里写的「给知伴的指令」（已在 user.md 注入）。
 */
enum class ResponseStyle(val label: String, val hint: String, val promptFragment: String) {
    CONCISE("简洁", "只给结论，少解释", "回答简洁：只给结论和必要信息，不展开解释。"),
    BALANCED("平衡", "默认，先结论后展开", "回答平衡：先给结论，再简要展开要点。"),
    DETAILED("详细", "充分展开，讲清来龙去脉", "回答详细：充分展开，讲清来龙去脉和依据。"),
    CASUAL("轻松", "像朋友聊天，随意自然", "回答轻松：像朋友聊天，语气随意自然。"),
    PROFESSIONAL("专业", "正式高效，直给要点", "回答专业：正式高效，直给要点，不闲聊。"),
    PLAYFUL("活泼", "有趣幽默，适度用 emoji", "回答活泼：有趣幽默，可适度使用 emoji。"),
    CUSTOM("自定义", "用「给知伴的指令」", ""),
}

/** Agent-owned preferences. Generic app settings must not interpret or mutate these fields. */
@Singleton
class AgentPersonalizationStore internal constructor(context: Context, legacyStoreName: String, secureStoreName: String) {
    @Inject constructor(@ApplicationContext context: Context) : this(context, LEGACY_STORE_NAME, SECURE_STORE_NAME)

    private val legacyStore = context.getSharedPreferences(legacyStoreName, Context.MODE_PRIVATE)
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    private val store = EncryptedSharedPreferences.create(
        context,
        secureStoreName,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun load(): Personalization {
        migrateLegacyIfNeeded()
        return Personalization(
            store.getString(KEY_PREFERRED_NAME, "").orEmpty(),
            runCatching { ResponseStyle.valueOf(store.getString(KEY_RESPONSE_STYLE, ResponseStyle.BALANCED.name)!!) }
                .getOrDefault(ResponseStyle.BALANCED),
        )
    }

    fun save(value: Personalization) {
        check(
            store.edit().putString(KEY_PREFERRED_NAME, value.preferredName.trim())
                .putString(KEY_RESPONSE_STYLE, value.style.name).commit(),
        )
        check(legacyStore.edit().clear().commit())
    }

    private fun migrateLegacyIfNeeded() {
        if (store.contains(KEY_PREFERRED_NAME) || store.contains(KEY_RESPONSE_STYLE)) return
        if (!legacyStore.contains(KEY_PREFERRED_NAME) && !legacyStore.contains(KEY_RESPONSE_STYLE)) return
        val legacy = Personalization(
            preferredName = legacyStore.getString(KEY_PREFERRED_NAME, "").orEmpty(),
            style = runCatching {
                ResponseStyle.valueOf(legacyStore.getString(KEY_RESPONSE_STYLE, ResponseStyle.BALANCED.name)!!)
            }.getOrDefault(ResponseStyle.BALANCED),
        )
        save(legacy)
    }

    private companion object {
        const val LEGACY_STORE_NAME = "agent_personalization"
        const val SECURE_STORE_NAME = "agent_personalization_secure"
        const val KEY_PREFERRED_NAME = "preferred_name"
        const val KEY_RESPONSE_STYLE = "response_style"
    }
}
