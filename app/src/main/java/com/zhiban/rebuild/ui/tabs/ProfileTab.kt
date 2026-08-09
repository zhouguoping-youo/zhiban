package com.zhiban.rebuild.ui.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.DataUsage
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhiban.rebuild.runtime.personalization.UserProfileStore
import com.zhiban.rebuild.ui.components.ZhiBanLeadingIcon
import com.zhiban.rebuild.ui.components.ZhiBanPage
import com.zhiban.rebuild.ui.components.ZhiBanPrimaryTabHeader
import com.zhiban.rebuild.ui.components.ZhiBanTabHorizontalPadding
import com.zhiban.rebuild.ui.components.ZhiBanTabTopPadding
import com.zhiban.rebuild.ui.theme.DangerRed
import com.zhiban.rebuild.ui.theme.ZhiBanCard
import com.zhiban.rebuild.ui.theme.ZhiBanDivider
import com.zhiban.rebuild.ui.theme.ZhiBanRadius
import com.zhiban.rebuild.ui.theme.ZhiBanSize
import com.zhiban.rebuild.ui.theme.ZhiBanSpacing
import com.zhiban.rebuild.ui.theme.ZhiBanTerracotta
import com.zhiban.rebuild.ui.theme.ZhiBanTerracottaSoft
import com.zhiban.rebuild.ui.theme.ZhiBanTextPrimary
import com.zhiban.rebuild.ui.theme.ZhiBanTextSecondary
import com.zhiban.rebuild.ui.theme.ZhiBanWarmBackground
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

private val SettingsCanvas: Color @Composable get() = MaterialTheme.colorScheme.background
private val SettingsSurface: Color @Composable get() = MaterialTheme.colorScheme.surface
private val SettingsPrimary: Color @Composable get() = MaterialTheme.colorScheme.onBackground
private val SettingsSecondary: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
private val SettingsIconSurface: Color @Composable get() = MaterialTheme.colorScheme.primaryContainer
private val SettingsDivider: Color @Composable get() = MaterialTheme.colorScheme.outlineVariant
private val SettingsDanger: Color @Composable get() = MaterialTheme.colorScheme.error

@HiltViewModel
class ProfileTabViewModel @Inject constructor(store: UserProfileStore) : ViewModel() {
    val profile = store.profile
}

private data class ProfileSettingItem(val icon: ImageVector, val title: String, val subtitle: String, val isDanger: Boolean = false, val onClick: () -> Unit)

@Composable
fun ProfileTab(
    onNavigateToAgentSettings: () -> Unit = {},
    onNavigateToAutoWrites: () -> Unit = {},
    onNavigateToProfileEdit: () -> Unit = {},
    onNavigateToPrivacySecurity: () -> Unit = {},
    onNavigateToAppearance: () -> Unit = {},
    onNavigateToNotificationSettings: () -> Unit = {},
    onNavigateToStorage: () -> Unit = {},
    onNavigateToData: () -> Unit = {},
    onNavigateToReportError: () -> Unit = {},
    onNavigateToAbout: () -> Unit = {},
    modifier: Modifier = Modifier,
    isDataEmpty: Boolean = false,
    viewModel: ProfileTabViewModel = hiltViewModel(),
    autoWriteViewModel: com.zhiban.rebuild.ui.settings.AutoWriteViewModel = hiltViewModel(),
) {
    if (isDataEmpty) {
        MainTabEmptyPage("profile", modifier)
        return
    }

    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val autoWriteState by autoWriteViewModel.state.collectAsStateWithLifecycle()
    val pendingAutoWriteCount = autoWriteState.receipts.count { it.reviewState == "UNREVIEWED" }
    val preferredName = profile.preferredName.ifBlank { profile.name }
    val hasProfile = preferredName.isNotBlank() || profile.phone.isNotBlank()
    val identityTitle = preferredName.ifBlank { "完善个人资料" }
    val identitySubtitle = when {
        profile.preferredName.isNotBlank() -> "知伴会称呼你为“${profile.preferredName}”"
        profile.name.isNotBlank() -> "设置知伴平时怎么称呼你"
        else -> "告诉知伴你的称呼和联系方式"
    }

    val agentItems = listOf(
        ProfileSettingItem(
            icon = Icons.Outlined.Psychology,
            title = "智能体设置",
            subtitle = "大模型、记忆、对话风格和工具",
            onClick = onNavigateToAgentSettings,
        ),
        ProfileSettingItem(
            icon = Icons.Outlined.AutoAwesome,
            title = "知伴帮你记的",
            subtitle = if (pendingAutoWriteCount > 0) {
                "$pendingAutoWriteCount 条待查看 · 可撤销或纠正"
            } else {
                "查看、撤销或纠正知伴自动记录的内容"
            },
            onClick = onNavigateToAutoWrites,
        ),
    )
    val appItems = listOf(
        ProfileSettingItem(Icons.Outlined.DarkMode, "外观", "跟随手机显示设置", onClick = onNavigateToAppearance),
        ProfileSettingItem(
            Icons.Outlined.NotificationsNone,
            "通知",
            "提醒和消息权限",
            onClick = onNavigateToNotificationSettings,
        ),
        ProfileSettingItem(Icons.Outlined.Lock, "隐私与权限", "查看知伴当前可使用的权限", onClick = onNavigateToPrivacySecurity),
        ProfileSettingItem(Icons.Outlined.Storage, "存储", "查看和清理临时文件", onClick = onNavigateToStorage),
        ProfileSettingItem(Icons.Outlined.DataUsage, "数据管理", "对话、记忆、联系人、日程与诊断", onClick = onNavigateToData),
    )
    val supportItems = listOf(
        ProfileSettingItem(Icons.Outlined.BugReport, "报告问题", "发送问题描述或导出诊断", onClick = onNavigateToReportError),
        ProfileSettingItem(Icons.Outlined.Info, "关于知伴", "版本、隐私与产品信息", onClick = onNavigateToAbout),
    )

    ZhiBanPage(modifier = modifier) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(SettingsCanvas)
                .padding(horizontal = ZhiBanTabHorizontalPadding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                top = ZhiBanTabTopPadding,
                bottom = ZhiBanSize.BottomBar + ZhiBanSpacing.Xxxl * 2,
            ),
            verticalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Xl),
        ) {
            item {
                ZhiBanPrimaryTabHeader(
                    title = "我的",
                    subtitle = "管理资料与设置",
                )
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(ZhiBanRadius.Card))
                        .background(SettingsSurface)
                        .clickable(onClick = onNavigateToProfileEdit)
                        .semantics {
                            contentDescription = "$identityTitle，$identitySubtitle，进入个人设置"
                        }
                        .padding(ZhiBanSpacing.Lg),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(ZhiBanTerracotta),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (hasProfile) identityTitle.first().toString() else "我",
                            style = MaterialTheme.typography.titleLarge,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(Modifier.width(ZhiBanSpacing.Lg))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            identityTitle,
                            style = MaterialTheme.typography.titleMedium,
                            color = SettingsPrimary,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            identitySubtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = SettingsSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Icon(
                        Icons.Outlined.ChevronRight,
                        contentDescription = null,
                        tint = SettingsSecondary,
                        modifier = Modifier.size(ZhiBanSize.IconSmall),
                    )
                }
            }

            item { ProfileSettingsGroup("知伴", agentItems) }
            item { ProfileSettingsGroup("APP 设置", appItems) }
            item { ProfileSettingsGroup("帮助与关于", supportItems) }
        }
    }
}

@Composable
private fun ProfileSettingsGroup(title: String?, items: List<ProfileSettingItem>) {
    Column(verticalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Sm)) {
        if (title != null) {
            Text(
                title,
                style = MaterialTheme.typography.labelMedium,
                color = SettingsSecondary,
                modifier = Modifier.padding(horizontal = ZhiBanSpacing.Xs),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(ZhiBanRadius.Card))
                .background(SettingsSurface),
        ) {
            items.forEachIndexed { index, item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = ZhiBanSize.ListRowWithSubtitle)
                        .clickable(onClick = item.onClick)
                        .semantics {
                            contentDescription = "${item.title}，${item.subtitle}"
                        }
                        .padding(horizontal = ZhiBanSpacing.Lg, vertical = ZhiBanSpacing.Md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ZhiBanLeadingIcon(
                        item.icon,
                        tint = if (item.isDanger) SettingsDanger else ZhiBanTerracotta,
                        containerColor = SettingsIconSurface,
                    )
                    Spacer(Modifier.width(ZhiBanSpacing.Md))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(
                            item.title,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (item.isDanger) SettingsDanger else SettingsPrimary,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            item.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = SettingsSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.width(ZhiBanSpacing.Sm))
                    Icon(
                        Icons.Outlined.ChevronRight,
                        contentDescription = null,
                        tint = SettingsSecondary,
                        modifier = Modifier.size(ZhiBanSize.IconSmall),
                    )
                }
                if (index < items.lastIndex) {
                    HorizontalDivider(
                        color = SettingsDivider,
                        modifier = Modifier.padding(start = 64.dp),
                    )
                }
            }
        }
    }
}
