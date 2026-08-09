package com.zhiban.rebuild.data.notification

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.notificationCategoryDataStore by preferencesDataStore("notification_categories")

/** 应用内通知分类开关。默认全开；系统通知权限仍是总闸门。 */
enum class NotificationCategory(val title: String, val subtitle: String) {
    SCHEDULE("日程提醒", "日程到期提醒"),
    CRM("CRM 提醒", "跟进待办提醒"),
    COLLECTION("采集通知", "新消息 / 新通话采集提示"),
    AUTO_WRITE("自动整理", "自动写入回执提示"),
    ;

    val preferenceKey get() = booleanPreferencesKey(storageKey)

    private val storageKey get() = when (this) {
        SCHEDULE -> "notification_schedule"
        CRM -> "notification_crm"
        COLLECTION -> "notification_collection"
        AUTO_WRITE -> "notification_autowrite"
    }
}

@Singleton
class NotificationCategoryPreferences @Inject constructor(@ApplicationContext private val context: Context) {
    fun isEnabled(category: NotificationCategory): Flow<Boolean> = context.notificationCategoryDataStore.data.map { it[category.preferenceKey] ?: true }

    suspend fun isEnabledNow(category: NotificationCategory): Boolean = isEnabled(category).first()

    suspend fun setEnabled(category: NotificationCategory, enabled: Boolean) {
        context.notificationCategoryDataStore.edit { it[category.preferenceKey] = enabled }
    }
}
