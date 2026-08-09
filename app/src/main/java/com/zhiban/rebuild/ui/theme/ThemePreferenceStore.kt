package com.zhiban.rebuild.ui.theme

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 浅色 / 深色 / 跟随系统的显示偏好。 */
enum class ThemePreference(val storageValue: String) {
    LIGHT("light"),
    DARK("dark"),
    SYSTEM("system"),
    ;

    companion object {
        const val DEFAULT_STORAGE_VALUE = "system"

        fun fromStorage(value: String?): ThemePreference = entries.firstOrNull { it.storageValue == value } ?: SYSTEM
    }
}

/** 把持久化的显示偏好解析成 Compose 主题使用的暗色开关。 */
@Composable
fun ThemePreference.resolvesToDarkTheme(): Boolean = when (this) {
    ThemePreference.LIGHT -> false
    ThemePreference.DARK -> true
    ThemePreference.SYSTEM -> isSystemInDarkTheme()
}

/**
 * 显示偏好的单一来源。外观属于非敏感设置，用普通 SharedPreferences 保存，
 * 与加密个人资料（UserProfileStore）区分。
 */
@Singleton
class ThemePreferenceStore @Inject constructor(@ApplicationContext context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val mutablePreference = MutableStateFlow(ThemePreference.fromStorage(preferences.getString(KEY, null)))
    val preference = mutablePreference.asStateFlow()

    fun setPreference(value: ThemePreference) {
        preferences.edit().putString(KEY, value.storageValue).apply()
        mutablePreference.value = value
    }

    companion object {
        const val PREFERENCES_NAME = "theme_preference"
        private const val KEY = "theme_preference"
    }
}
