package com.zhiban.rebuild.runtime.governance

import android.content.Context
import com.zhiban.rebuild.runtime.provider.OutboundPolicySettings
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
        // Cloud speech is only invoked after an explicit user action (voice input or call note).
        // Keep it ready by default so the feature does not fail behind a technical setting.
        allowCloudSpeech = preferences.getBoolean(KEY_ALLOW_CLOUD_SPEECH, true),
        allowRemoteMcp = preferences.getBoolean(KEY_ALLOW_REMOTE_MCP, false),
        allowRemoteEmbedding = preferences.getBoolean(KEY_ALLOW_REMOTE_EMBEDDING, false),
        allowWechatIlink = preferences.getBoolean(KEY_ALLOW_WECHAT_ILINK, false),
    )

    fun setAllowRedactedAutomaticPersonalContext(enabled: Boolean) {
        check(preferences.edit().putBoolean(KEY_ALLOW_REDACTED_AUTOMATIC_PERSONAL_CONTEXT, enabled).commit())
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

    fun setAllowWechatIlink(enabled: Boolean) {
        check(preferences.edit().putBoolean(KEY_ALLOW_WECHAT_ILINK, enabled).commit())
    }

    private companion object {
        const val KEY_ALLOW_REDACTED_AUTOMATIC_PERSONAL_CONTEXT =
            "allow_redacted_automatic_personal_context"
        const val KEY_ALLOW_CLOUD_SPEECH = "allow_cloud_speech"
        const val KEY_ALLOW_REMOTE_MCP = "allow_remote_mcp"
        const val KEY_ALLOW_REMOTE_EMBEDDING = "allow_remote_embedding"
        const val KEY_ALLOW_WECHAT_ILINK = "allow_wechat_ilink"
    }
}
