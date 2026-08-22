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
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.lifecycle.viewModelScope
import com.zhiban.rebuild.provider.ProviderEnvironmentManager
import com.zhiban.rebuild.runtime.personalization.UserProfileStore
import com.zhiban.rebuild.ui.components.ZhiBanPage
import com.zhiban.rebuild.ui.components.ZhiBanPrimaryTabHeader
import com.zhiban.rebuild.ui.components.ZhiBanTabHorizontalPadding
import com.zhiban.rebuild.ui.components.ZhiBanTabTopPadding
import com.zhiban.rebuild.ui.debug.DebugAcceptanceEntry
import com.zhiban.rebuild.ui.theme.ZhiBanRadius
import com.zhiban.rebuild.ui.theme.ZhiBanSize
import com.zhiban.rebuild.ui.theme.ZhiBanSpacing
import com.zhiban.rebuild.ui.theme.ZhiBanTerracotta
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private val SettingsCanvas: Color @Composable get() = MaterialTheme.colorScheme.background
private val SettingsSurface: Color @Composable get() = MaterialTheme.colorScheme.surface
private val SettingsPrimary: Color @Composable get() = MaterialTheme.colorScheme.onBackground
private val SettingsSecondary: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
private val SettingsDivider: Color @Composable get() = MaterialTheme.colorScheme.outlineVariant
private val SettingsDanger: Color @Composable get() = MaterialTheme.colorScheme.error

@HiltViewModel
class ProfileTabViewModel @Inject constructor(store: UserProfileStore, private val provider: ProviderEnvironmentManager) : ViewModel() {
    val profile = store.profile
    private val mutableProviderConfigured = MutableStateFlow<Boolean?>(null)
    val providerConfigured = mutableProviderConfigured.asStateFlow()

    init {
        refreshProviderState()
    }

    fun refreshProviderState() {
        viewModelScope.launch {
            mutableProviderConfigured.value = provider.isConfigured()
        }
    }
}

internal data class ProfileSettingItem(
    val icon: ImageVector,
    val title: String,
    val accessibilityDescription: String,
    val statusText: String? = null,
    val statusNeedsAttention: Boolean = false,
    val isDanger: Boolean = false,
    val onClick: () -> Unit,
)

@Composable
fun ProfileTab(
    onNavigateToAgentSettings: () -> Unit = {},
    onNavigateToModelConfig: () -> Unit = {},
    onNavigateToAutoWrites: () -> Unit = {},
    onNavigateToAgentSuggestions: () -> Unit = {},
    onNavigateToProfileEdit: () -> Unit = {},
    onNavigateToPrivacySecurity: () -> Unit = {},
    onNavigateToAppearance: () -> Unit = {},
    onNavigateToNotificationSettings: () -> Unit = {},
    onNavigateToData: () -> Unit = {},
    onNavigateToReportError: () -> Unit = {},
    onNavigateToAbout: () -> Unit = {},
    onNavigateToDebugAcceptance: () -> Unit = {},
    modifier: Modifier = Modifier,
    isDataEmpty: Boolean = false,
    viewModel: ProfileTabViewModel = hiltViewModel(),
    autoWriteViewModel: com.zhiban.rebuild.ui.settings.AutoWriteViewModel = hiltViewModel(),
    agentSuggestionViewModel: com.zhiban.rebuild.ui.settings.AgentSuggestionViewModel = hiltViewModel(),
) {
    if (isDataEmpty) {
        MainTabEmptyPage("profile", modifier)
        return
    }

    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val providerConfigured by viewModel.providerConfigured.collectAsStateWithLifecycle()
    val autoWriteState by autoWriteViewModel.state.collectAsStateWithLifecycle()
    val pendingSuggestionCount by agentSuggestionViewModel.pendingCount.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refreshProviderState() }
    val pendingAutoWriteCount = autoWriteState.receipts.count { it.reviewState == "UNREVIEWED" }
    val preferredName = profile.preferredName.ifBlank { profile.name }
    val hasProfile = preferredName.isNotBlank() || profile.phone.isNotBlank()
    val identityTitle = preferredName.ifBlank { "完善个人资料" }
    val identitySubtitle = when {
        profile.preferredName.isNotBlank() -> "知伴会称呼你为“${profile.preferredName}”"
        profile.name.isNotBlank() -> "设置知伴平时怎么称呼你"
        else -> "告诉知伴你的称呼和联系方式"
    }

    // §九：L1 只留日常入口（资料/权限/模型连接/通知/外观/存储和数据/自动整理/帮助），
    // 记忆、工具、运行记录等高级与诊断项统一收进「智能体设置」。
    val agentItems = listOf(
        ProfileSettingItem(
            icon = Icons.Outlined.CloudQueue,
            title = "模型连接",
            accessibilityDescription = "查看或更换知伴使用的 AI 服务",
            statusText = when (providerConfigured) {
                true -> "已连接"
                false -> "未连接"
                null -> null
            },
            statusNeedsAttention = providerConfigured == false,
            onClick = onNavigateToModelConfig,
        ),
        ProfileSettingItem(
            icon = Icons.Outlined.Lightbulb,
            title = "智能建议",
            accessibilityDescription = "知伴主动发现的事项与下一步行动",
            statusText = pendingSuggestionCount.takeIf { it > 0 }?.let { "$it 条新建议" },
            statusNeedsAttention = pendingSuggestionCount > 0,
            onClick = onNavigateToAgentSuggestions,
        ),
        ProfileSettingItem(
            icon = Icons.Outlined.AutoAwesome,
            title = "自动整理记录",
            accessibilityDescription = "查看、撤销或纠正知伴自动记录的内容",
            statusText = pendingAutoWriteCount.takeIf { it > 0 }?.let { "$it 条待查看" },
            statusNeedsAttention = pendingAutoWriteCount > 0,
            onClick = onNavigateToAutoWrites,
        ),
    )
    val appItems = listOf(
        ProfileSettingItem(Icons.Outlined.Lock, "手机权限", "知伴可使用的权限与数据范围", onClick = onNavigateToPrivacySecurity),
        ProfileSettingItem(
            Icons.Outlined.NotificationsNone,
            "通知",
            "提醒和消息权限",
            onClick = onNavigateToNotificationSettings,
        ),
        ProfileSettingItem(Icons.Outlined.DarkMode, "外观", "跟随手机显示设置", onClick = onNavigateToAppearance),
        ProfileSettingItem(Icons.Outlined.Storage, "存储和数据", "占用空间、导出与重置", onClick = onNavigateToData),
    )
    val advancedItems = listOf(
        ProfileSettingItem(
            Icons.Outlined.Psychology,
            "智能体设置",
            "记忆、回答偏好与工具",
            onClick = onNavigateToAgentSettings,
        ),
    )
    val supportItems = listOf(
        ProfileSettingItem(Icons.Outlined.BugReport, "帮助与问题反馈", "发送问题描述或导出诊断", onClick = onNavigateToReportError),
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
            verticalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Lg),
        ) {
            item {
                ZhiBanPrimaryTabHeader(
                    title = "我的",
                    subtitle = "资料、权限与数据",
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
                        .padding(horizontal = ZhiBanSpacing.Lg, vertical = ZhiBanSpacing.Md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(ZhiBanSize.Avatar)
                            .clip(CircleShape)
                            .background(ZhiBanTerracotta),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (hasProfile) identityTitle.first().toString() else "我",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onPrimary,
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
            item { ProfileSettingsGroup("设置", appItems) }
            item { ProfileSettingsGroup("高级", advancedItems) }
            item { ProfileSettingsGroup("支持", supportItems) }
            // Debug 构建才渲染的验收入口；Release 变体为 no-op。
            item { DebugAcceptanceEntry(onClick = onNavigateToDebugAcceptance) }
        }
    }
}

@Composable
internal fun ProfileSettingsGroup(title: String?, items: List<ProfileSettingItem>) {
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
                        .defaultMinSize(minHeight = 56.dp)
                        .clickable(onClick = item.onClick)
                        .semantics {
                            contentDescription = listOfNotNull(
                                item.title,
                                item.statusText,
                                item.accessibilityDescription,
                            ).joinToString("，")
                        }
                        .padding(horizontal = ZhiBanSpacing.Lg, vertical = ZhiBanSpacing.Sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier.size(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            item.icon,
                            contentDescription = null,
                            tint = if (item.isDanger) SettingsDanger else SettingsSecondary,
                            modifier = Modifier.size(ZhiBanSize.Icon),
                        )
                    }
                    Spacer(Modifier.width(ZhiBanSpacing.Md))
                    Text(
                        item.title,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (item.isDanger) SettingsDanger else SettingsPrimary,
                        fontWeight = FontWeight.Medium,
                    )
                    item.statusText?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (item.statusNeedsAttention) ZhiBanTerracotta else SettingsSecondary,
                            maxLines = 1,
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
