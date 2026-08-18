package com.zhiban.rebuild.ui.settings

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.zhiban.rebuild.data.notification.NotificationCategory
import com.zhiban.rebuild.data.notification.NotificationCategoryPreferences
import com.zhiban.rebuild.ui.theme.ZhiBanSpacing
import com.zhiban.rebuild.ui.theme.ZhiBanTextSecondary
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Stable outbound privacy contract used by policy regression tests; not rendered as a technical settings table. */
internal enum class DataSendScope(val dataType: String, val isSent: Boolean, val note: String) {
    CONTACT_IDENTITY("联系人资料（脱敏）", true, "脱敏后发送，用于识别和称呼"),
    DIRECT_IDENTIFIER("手机号与邮箱", false, "始终脱敏，不会原样发送"),
    MESSAGE_CONTENT("消息原文", false, "只提炼摘要，原文不发送"),
    CALL_LOG("通话记录", false, "只保存时间与方向等元数据"),
    VOICE("语音（授权后）", true, "仅在你授权后发给语音识别"),
    ;

    val statusLabel: String get() = if (isSent) "发送" else "不发送"
}

@HiltViewModel
class NotificationCategoryViewModel @Inject constructor(private val preferences: NotificationCategoryPreferences) : ViewModel() {
    private val mutableStates = MutableStateFlow(NotificationCategory.entries.associateWith { true })
    val states = mutableStates.asStateFlow()

    init {
        NotificationCategory.entries.forEach { category ->
            viewModelScope.launch {
                preferences.isEnabled(category).collect { enabled ->
                    mutableStates.update { it + (category to enabled) }
                }
            }
        }
    }

    fun setEnabled(category: NotificationCategory, enabled: Boolean) {
        mutableStates.update { it + (category to enabled) }
        viewModelScope.launch { preferences.setEnabled(category, enabled) }
    }
}

@Composable
fun NotificationSettingsPage(onBack: () -> Unit, categoryViewModel: NotificationCategoryViewModel = hiltViewModel()) {
    val context = LocalContext.current
    var refreshVersion by remember { mutableIntStateOf(0) }
    RefreshPermissionsOnResume { refreshVersion += 1 }
    val notificationsGranted = remember(refreshVersion) {
        NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        refreshVersion += 1
    }
    val categoryStates by categoryViewModel.states.collectAsStateWithLifecycle()

    SettingsPageFrame("通知", onBack) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ZhiBanSpacing.PageHorizontal)
                .padding(bottom = ZhiBanSpacing.PageBottom),
            verticalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Md),
        ) {
            SettingsCard {
                SettingsPermissionRow(
                    Icons.Outlined.NotificationsNone,
                    "系统通知",
                    if (notificationsGranted) "已开启 · 点击管理" else "未开启 · 点击允许",
                ) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationsGranted) {
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        context.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            },
                        )
                    }
                }
            }
            Text(
                "通知分类",
                style = MaterialTheme.typography.labelMedium,
                color = ZhiBanTextSecondary,
                modifier = Modifier.padding(horizontal = ZhiBanSpacing.Xs),
            )
            SettingsCard {
                NotificationCategory.entries.forEachIndexed { index, category ->
                    if (index > 0) Divider()
                    SettingsToggleRow(
                        title = category.title,
                        subtitle = category.subtitle,
                        checked = categoryStates[category] ?: true,
                        onCheckedChange = { enabled -> categoryViewModel.setEnabled(category, enabled) },
                    )
                }
            }
        }
    }
}
