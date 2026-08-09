package com.zhiban.rebuild.ui.chat

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private val Context.dataStore by preferencesDataStore("zhiban_prefs")

/**
 * 统一管理用户配置。
 * 通用设置。Provider Key 不得通过此类读写；用户自定义提示词可能包含个人信息，
 * 因此单独保存在 EncryptedSharedPreferences，并迁走旧版 DataStore 明文。
 */
@Singleton
class PreferencesManager @Inject constructor(@ApplicationContext private val context: Context) {
    private val modelKey = stringPreferencesKey("model")
    private val systemPromptKey = stringPreferencesKey("system_prompt")
    private val activeAgentRunIdKey = stringPreferencesKey("active_agent_run_id")
    private val activeRuntimeSessionIdKey = stringPreferencesKey("active_runtime_session_id")
    private val agentModeKey = stringPreferencesKey("active_agent_mode")

    private val dataStore = context.dataStore

    private val encryptedPrefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            SECURE_PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    suspend fun clearLegacyApiKey() = withContext(Dispatchers.IO) {
        encryptedPrefs.edit().remove(API_KEY_FIELD).apply()
    }

    suspend fun consumeLegacyApiKey(block: suspend (ByteArray) -> Unit): Boolean = withContext(Dispatchers.IO) {
        val legacy = encryptedPrefs.getString(API_KEY_FIELD, null)?.takeIf { it.isNotBlank() }
            ?: return@withContext false
        val bytes = legacy.toByteArray(Charsets.UTF_8)
        try {
            block(bytes)
            check(encryptedPrefs.edit().remove(API_KEY_FIELD).commit())
            true
        } finally {
            bytes.fill(0)
        }
    }

    // ===== Model / System Prompt =====

    suspend fun getModel(): String = dataStore.data.map { it[modelKey] ?: DEFAULT_MODEL }.first()

    suspend fun saveModel(model: String) {
        dataStore.edit { it[modelKey] = model }
    }

    suspend fun getSystemPrompt(): String {
        val encrypted = withContext(Dispatchers.IO) { encryptedPrefs.getString(SYSTEM_PROMPT_FIELD, null) }
        if (encrypted != null) return encrypted
        val legacy = dataStore.data.map { it[systemPromptKey] ?: "" }.first()
        if (legacy.isNotEmpty()) saveSystemPrompt(legacy)
        return legacy
    }

    suspend fun saveSystemPrompt(prompt: String) {
        withContext(Dispatchers.IO) {
            check(encryptedPrefs.edit().putString(SYSTEM_PROMPT_FIELD, prompt).commit())
        }
        dataStore.edit { it.remove(systemPromptKey) }
    }

    suspend fun getActiveAgentRunId(): String? = dataStore.data
        .map { it[activeAgentRunIdKey] }
        .first()

    suspend fun saveActiveAgentRunId(runId: String) {
        dataStore.edit { it[activeAgentRunIdKey] = runId }
    }

    suspend fun clearActiveAgentRunId() {
        dataStore.edit { it.remove(activeAgentRunIdKey) }
    }

    suspend fun getActiveRuntimeSessionId(): String? = dataStore.data
        .map { it[activeRuntimeSessionIdKey] }
        .first()

    suspend fun saveActiveRuntimeSessionId(sessionId: String) {
        dataStore.edit { it[activeRuntimeSessionIdKey] = sessionId }
    }

    suspend fun getAgentMode(): String = dataStore.data
        .map { it[agentModeKey]?.takeIf { mode -> mode == "Work" } ?: "Chat" }
        .first()

    suspend fun saveAgentMode(mode: String) {
        dataStore.edit { it[agentModeKey] = if (mode == "Work") "Work" else "Chat" }
    }

    suspend fun saveNonSecretModelSettings(model: String, systemPrompt: String) {
        saveModel(model)
        saveSystemPrompt(systemPrompt)
    }

    companion object {
        private const val SECURE_PREFS_NAME = "zhiban_secure_prefs"
        private const val API_KEY_FIELD = "api_key"
        private const val SYSTEM_PROMPT_FIELD = "system_prompt"
        private const val DEFAULT_MODEL = "step-3.5-flash"
    }
}
