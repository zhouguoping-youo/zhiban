package com.zhiban.rebuild.runtime.governance

import android.content.Context
import com.zhiban.rebuild.provider.OutboundPolicySettings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OutboundDataPreferences @Inject constructor(@ApplicationContext context: Context) {
    private val preferences = context.getSharedPreferences("outbound_data_preferences", Context.MODE_PRIVATE)

    fun snapshot(): OutboundPolicySettings = OutboundPolicySettings(
        allowRedactedAutomaticPersonalContext = preferences.getBoolean(
            KEY_ALLOW_REDACTED_AUTOMATIC_PERSONAL_CONTEXT,
            true,
        ),
        // 云语音只有显式操作(语音输入/通话备注)才调用;与其他出站通道一致 fail-closed,
        // 默认关闭,首次使用时由界面引导开启(复检 P1-2)。
        allowCloudSpeech = preferences.getBoolean(KEY_ALLOW_CLOUD_SPEECH, false),
        allowCloudLlm = preferences.getBoolean(KEY_ALLOW_CLOUD_LLM, true),
        allowRemoteMcp = preferences.getBoolean(KEY_ALLOW_REMOTE_MCP, false),
        allowRemoteEmbedding = preferences.getBoolean(KEY_ALLOW_REMOTE_EMBEDDING, false),
        allowUnmaskedPhoneNumbers = preferences.getBoolean(KEY_ALLOW_UNMASKED_PHONE_NUMBERS, true),
    )

    fun setAllowRedactedAutomaticPersonalContext(enabled: Boolean) {
        check(preferences.edit().putBoolean(KEY_ALLOW_REDACTED_AUTOMATIC_PERSONAL_CONTEXT, enabled).commit())
    }

    fun setAllowCloudLlm(enabled: Boolean) {
        check(preferences.edit().putBoolean(KEY_ALLOW_CLOUD_LLM, enabled).commit())
    }

    fun setAllowCloudSpeech(enabled: Boolean) {
        check(preferences.edit().putBoolean(KEY_ALLOW_CLOUD_SPEECH, enabled).commit())
    }

    fun setAllowRemoteMcp(enabled: Boolean) {
        check(preferences.edit().putBoolean(KEY_ALLOW_REMOTE_MCP, enabled).commit())
    }

    fun setAllowRemoteEmbedding(enabled: Boolean) {
        check(preferences.edit().putBoolean(KEY_ALLOW_REMOTE_EMBEDDING, enabled).commit())
    }

    fun setAllowUnmaskedPhoneNumbers(enabled: Boolean) {
        check(preferences.edit().putBoolean(KEY_ALLOW_UNMASKED_PHONE_NUMBERS, enabled).commit())
    }

    private companion object {
        const val KEY_ALLOW_REDACTED_AUTOMATIC_PERSONAL_CONTEXT =
            "allow_redacted_automatic_personal_context"
        const val KEY_ALLOW_CLOUD_SPEECH = "allow_cloud_speech"
        const val KEY_ALLOW_CLOUD_LLM = "allow_cloud_llm"
        const val KEY_ALLOW_REMOTE_MCP = "allow_remote_mcp"
        const val KEY_ALLOW_REMOTE_EMBEDDING = "allow_remote_embedding"
        const val KEY_ALLOW_UNMASKED_PHONE_NUMBERS = "allow_unmasked_phone_numbers"
    }
}
