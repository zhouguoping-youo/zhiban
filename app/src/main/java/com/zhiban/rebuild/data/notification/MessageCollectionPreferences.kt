package com.zhiban.rebuild.data.notification

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.messageCollectionDataStore by preferencesDataStore("message_collection")

@Singleton
class MessageCollectionPreferences @Inject constructor(@ApplicationContext private val context: Context) {
    private val enabledPlatformsKey = stringSetPreferencesKey("enabled_platforms")
    private val outgoingCollectionEnabledKey = booleanPreferencesKey("outgoing_collection_enabled")
    private val lastNotificationListenerDisconnectKey = longPreferencesKey("last_notification_listener_disconnect")
    private val notificationGapReasonKey = stringPreferencesKey("notification_gap_reason")

    val enabledPlatforms: Flow<Set<String>> = context.messageCollectionDataStore.data.map { values ->
        values[enabledPlatformsKey] ?: DEFAULT_PLATFORMS
    }
    val outgoingCollectionEnabled: Flow<Boolean> = context.messageCollectionDataStore.data.map { values ->
        values[outgoingCollectionEnabledKey] ?: false
    }

    suspend fun isEnabled(platform: String): Boolean = platform in enabledPlatforms.first()

    suspend fun setEnabled(platform: String, enabled: Boolean) {
        require(platform in SUPPORTED_PLATFORMS) { "不支持的消息来源" }
        context.messageCollectionDataStore.edit { values ->
            val current = (values[enabledPlatformsKey] ?: DEFAULT_PLATFORMS).toMutableSet()
            if (enabled) current += platform else current -= platform
            values[enabledPlatformsKey] = current
        }
    }

    suspend fun setOutgoingCollectionEnabled(enabled: Boolean) {
        context.messageCollectionDataStore.edit { values ->
            values[outgoingCollectionEnabledKey] = enabled
        }
    }

    suspend fun onNotificationListenerDisconnected(atEpochMs: Long = System.currentTimeMillis()) {
        context.messageCollectionDataStore.edit { values ->
            values[lastNotificationListenerDisconnectKey] = atEpochMs
            values.remove(notificationGapReasonKey)
        }
    }

    suspend fun markNotificationGapIfNeeded(nowEpochMs: Long = System.currentTimeMillis(), thresholdMs: Long) {
        val disconnectAt = context.messageCollectionDataStore.data.first()[lastNotificationListenerDisconnectKey] ?: return
        val gapMs = nowEpochMs - disconnectAt
        context.messageCollectionDataStore.edit { values ->
            values.remove(lastNotificationListenerDisconnectKey)
            if (gapMs >= thresholdMs) {
                values[notificationGapReasonKey] = "notification_gap:$disconnectAt:$gapMs:$nowEpochMs"
            } else {
                values.remove(notificationGapReasonKey)
            }
        }
    }

    suspend fun consumeNotificationGapReason(): String? {
        val current = context.messageCollectionDataStore.data.first()[notificationGapReasonKey] ?: return null
        context.messageCollectionDataStore.edit { values ->
            values.remove(notificationGapReasonKey)
        }
        return current
    }

    companion object {
        val SUPPORTED_PLATFORMS = setOf(
            "WECHAT",
            "SMS",
            "QQ",
            "TIM",
            "FEISHU",
            "LARK",
            "WEWORK",
            "DINGTALK",
        )

        // SMS can contain OTPs, reset links and financial alerts. It remains available as an
        // explicit choice, but is never collected before the user turns it on.
        val DEFAULT_PLATFORMS = SUPPORTED_PLATFORMS - "SMS"
    }
}
