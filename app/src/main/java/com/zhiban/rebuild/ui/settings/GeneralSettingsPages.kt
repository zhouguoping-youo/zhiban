package com.zhiban.rebuild.ui.settings

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.zhiban.rebuild.BuildConfig
import com.zhiban.rebuild.data.agent.AGENT_DATABASE_FILE_NAME
import com.zhiban.rebuild.data.calllog.CallLogAccessProbe
import com.zhiban.rebuild.data.calllog.CallLogAccessStatus
import com.zhiban.rebuild.data.export.AgentDataExportService
import com.zhiban.rebuild.data.notification.NotificationCategory
import com.zhiban.rebuild.data.notification.NotificationCategoryPreferences
import com.zhiban.rebuild.data.notification.OutgoingMessageAccessibilityService
import com.zhiban.rebuild.ui.components.ZhiBanLeadingIcon
import com.zhiban.rebuild.ui.components.ZhiBanPage
import com.zhiban.rebuild.ui.components.ZhiBanTopBar
import com.zhiban.rebuild.ui.theme.Gray200
import com.zhiban.rebuild.ui.theme.ThemePreference
import com.zhiban.rebuild.ui.theme.ThemePreferenceStore
import com.zhiban.rebuild.ui.theme.ZhiBanCard
import com.zhiban.rebuild.ui.theme.ZhiBanRadius
import com.zhiban.rebuild.ui.theme.ZhiBanSize
import com.zhiban.rebuild.ui.theme.ZhiBanSpacing
import com.zhiban.rebuild.ui.theme.ZhiBanTerracotta
import com.zhiban.rebuild.ui.theme.ZhiBanTextPrimary
import com.zhiban.rebuild.ui.theme.ZhiBanTextSecondary
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Composable
private fun SettingsPageFrame(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    ZhiBanPage {
        Column(Modifier.fillMaxSize()) {
            ZhiBanTopBar(title = title, onBack = onBack)
            content()
        }
    }
}

@Composable
fun LanguageSettingsPage(onBack: () -> Unit) {
    SettingsPageFrame("语言", onBack) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ZhiBanSpacing.PageHorizontal)
                .padding(bottom = ZhiBanSpacing.PageBottom),
        ) {
            SettingsCard {
                SettingsRow("简体中文", "当前使用", trailing = {
                    Icon(Icons.Outlined.Check, contentDescription = null, tint = ZhiBanTerracotta)
                })
            }
        }
    }
}

@Composable
fun PrivacySecurityPage(
    onBack: () -> Unit,
    onOpenMemory: () -> Unit,
    onOpenTools: () -> Unit,
    outboundViewModel: OutboundPrivacyViewModel = hiltViewModel(),
    callCollectionViewModel: CallCollectionViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val outboundState by outboundViewModel.state.collectAsStateWithLifecycle()
    val callCollectionState by callCollectionViewModel.state.collectAsStateWithLifecycle()
    var pendingOutboundConsent by remember { mutableStateOf<OutboundConsentType?>(null) }
    var callLogAccessStatus by remember { mutableStateOf(CallLogAccessStatus.NOT_GRANTED) }
    var refreshVersion by remember { mutableIntStateOf(0) }
    RefreshPermissionsOnResume { refreshVersion += 1 }
    var permissions by remember { mutableStateOf(PermissionSnapshot.Defaults) }
    LaunchedEffect(refreshVersion) {
        permissions = PermissionSnapshot.read(context)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        refreshVersion += 1
    }
    val callLogPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            refreshVersion += 1
            if (isGranted) callCollectionViewModel.setEnabled(true)
        }
    val phoneStatePermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            refreshVersion += 1
            if (isGranted) callCollectionViewModel.setHangupNoteEnabled(true)
        }

    LaunchedEffect(refreshVersion, permissions.callLog.isGranted) {
        callLogAccessStatus = CallLogAccessProbe.probe(context.applicationContext)
    }

    fun requestOrOpen(permission: String, isGranted: Boolean) {
        if (isGranted) {
            context.openAppDetails()
        } else {
            permissionLauncher.launch(permission)
        }
    }

    SettingsPageFrame("隐私与权限", onBack) {
        LazyColumn(
            Modifier.padding(horizontal = ZhiBanSpacing.PageHorizontal),
            verticalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Md),
        ) {
            item {
                Text(
                    "数据发送范围",
                    style = MaterialTheme.typography.labelMedium,
                    color = ZhiBanTextSecondary,
                    modifier = Modifier.padding(horizontal = ZhiBanSpacing.Xs),
                )
            }
            item {
                SettingsCard {
                    DataSendScope.entries.forEachIndexed { index, scope ->
                        if (index > 0) Divider()
                        DataSendScopeRow(scope)
                    }
                }
            }
            item {
                Text(
                    "手机权限",
                    style = MaterialTheme.typography.labelMedium,
                    color = ZhiBanTextSecondary,
                    modifier = Modifier.padding(horizontal = ZhiBanSpacing.Xs),
                )
            }
            item {
                SettingsCard {
                    SettingsPermissionRow(
                        Icons.Outlined.Mic,
                        "麦克风",
                        permissions.microphone.label,
                    ) { requestOrOpen(Manifest.permission.RECORD_AUDIO, permissions.microphone.isGranted) }
                    Divider()
                    SettingsPermissionRow(
                        Icons.Outlined.CameraAlt,
                        "相机",
                        permissions.camera.label,
                    ) { requestOrOpen(Manifest.permission.CAMERA, permissions.camera.isGranted) }
                    Divider()
                    SettingsPermissionRow(
                        Icons.Outlined.Contacts,
                        "联系人",
                        permissions.contacts.label,
                    ) { requestOrOpen(Manifest.permission.READ_CONTACTS, permissions.contacts.isGranted) }
                    Divider()
                    SettingsPermissionRow(
                        Icons.Outlined.CalendarMonth,
                        "日历",
                        permissions.calendar.label,
                    ) { requestOrOpen(Manifest.permission.READ_CALENDAR, permissions.calendar.isGranted) }
                    Divider()
                    SettingsPermissionRow(
                        Icons.Outlined.Phone,
                        "通话记录",
                        when (callLogAccessStatus) {
                            CallLogAccessStatus.AVAILABLE -> "已允许，可读取"
                            CallLogAccessStatus.RESTRICTED -> "系统或安装器未允许"
                            CallLogAccessStatus.UNAVAILABLE -> "暂时无法读取"
                            CallLogAccessStatus.NOT_GRANTED -> "未允许"
                        },
                    ) { requestOrOpen(Manifest.permission.READ_CALL_LOG, permissions.callLog.isGranted) }
                    Divider()
                    SettingsPermissionRow(
                        Icons.Outlined.FolderOpen,
                        "照片与文件",
                        "每次选择",
                    ) { context.openAppDetails() }
                }
            }
            item {
                Text(
                    "通话感知",
                    style = MaterialTheme.typography.labelMedium,
                    color = ZhiBanTextSecondary,
                    modifier = Modifier.padding(horizontal = ZhiBanSpacing.Xs, vertical = ZhiBanSpacing.Sm),
                )
            }
            item {
                SettingsCard {
                    SettingsToggleRow(
                        title = "同步通话记录",
                        subtitle = callCollectionState.lastResult
                            ?: "不录音",
                        checked = callCollectionState.enabled,
                        onCheckedChange = { enabled ->
                            if (!enabled) {
                                callCollectionViewModel.setEnabled(false)
                            } else if (callLogAccessStatus == CallLogAccessStatus.AVAILABLE) {
                                callCollectionViewModel.setEnabled(true)
                            } else {
                                callLogPermissionLauncher.launch(Manifest.permission.READ_CALL_LOG)
                            }
                        },
                    )
                    if (callCollectionState.enabled) {
                        Divider()
                        SettingsToggleRow(
                            title = "挂断后提醒补充要点",
                            subtitle = "不录音",
                            checked = callCollectionState.hangupNoteEnabled,
                            onCheckedChange = { enabled ->
                                if (!enabled) {
                                    callCollectionViewModel.setHangupNoteEnabled(false)
                                } else if (ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.READ_PHONE_STATE,
                                    ) == PackageManager.PERMISSION_GRANTED
                                ) {
                                    callCollectionViewModel.setHangupNoteEnabled(true)
                                } else {
                                    phoneStatePermissionLauncher.launch(Manifest.permission.READ_PHONE_STATE)
                                }
                            },
                        )
                        Divider()
                        SettingsActionRow(
                            if (callCollectionState.syncing) "正在同步" else "立即同步",
                            onClick = callCollectionViewModel::syncNow,
                        )
                    }
                }
            }
            item {
                Text(
                    "消息感知",
                    style = MaterialTheme.typography.labelMedium,
                    color = ZhiBanTextSecondary,
                    modifier = Modifier.padding(horizontal = ZhiBanSpacing.Xs, vertical = ZhiBanSpacing.Sm),
                )
            }
            item {
                SettingsCard {
                    SettingsPermissionRow(
                        Icons.Outlined.NotificationsNone,
                        "收到的消息",
                        if (permissions.isNotificationListener) "通知读取已开启" else "未开启通知读取",
                    ) {
                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    }
                    Divider()
                    SettingsPermissionRow(
                        Icons.Outlined.Sync,
                        "发出的消息",
                        if (permissions.outgoingAccessibility) "辅助功能已开启" else "未开启辅助功能",
                    ) {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                }
            }
            item {
                Text(
                    "模型数据发送",
                    style = MaterialTheme.typography.labelMedium,
                    color = ZhiBanTextSecondary,
                    modifier = Modifier.padding(horizontal = ZhiBanSpacing.Xs, vertical = ZhiBanSpacing.Sm),
                )
            }
            item {
                SettingsCard {
                    SettingsToggleRow(
                        title = "使用脱敏后的个人资料",
                        subtitle = "脱敏后发送",
                        checked = outboundState.allowRedactedAutomaticPersonalContext,
                        onCheckedChange = outboundViewModel::setAllowRedactedAutomaticPersonalContext,
                    )
                    Divider()
                    SettingsToggleRow(
                        title = "允许语音识别上云",
                        subtitle = "仅主动录音",
                        checked = outboundState.allowCloudSpeech,
                        onCheckedChange = { enabled ->
                            if (enabled) {
                                pendingOutboundConsent = OutboundConsentType.CLOUD_SPEECH
                            } else {
                                outboundViewModel.setAllowCloudSpeech(false)
                            }
                        },
                    )
                    Divider()
                    SettingsToggleRow(
                        title = "允许远程外部工具",
                        subtitle = "逐次确认",
                        checked = outboundState.allowRemoteMcp,
                        onCheckedChange = { enabled ->
                            if (enabled) {
                                pendingOutboundConsent = OutboundConsentType.REMOTE_MCP
                            } else {
                                outboundViewModel.setAllowRemoteMcp(false)
                            }
                        },
                    )
                    Divider()
                    SettingsToggleRow(
                        title = "允许远程语义检索",
                        subtitle = "当前未启用",
                        checked = outboundState.allowRemoteEmbedding,
                        onCheckedChange = { enabled ->
                            if (enabled) {
                                pendingOutboundConsent = OutboundConsentType.REMOTE_EMBEDDING
                            } else {
                                outboundViewModel.setAllowRemoteEmbedding(false)
                            }
                        },
                    )
                    Divider()
                    SettingsRow(
                        "发送与拦截记录",
                        "${outboundState.auditCount} 次 · 已拦截 ${outboundState.blockedCount} 次 · 已脱敏 ${outboundState.redactedCount} 条",
                    )
                    if (outboundState.auditCount > 0) {
                        Divider()
                        SettingsActionRow("清除发送记录", outboundViewModel::clearAudit)
                    }
                }
            }
        }
    }
    pendingOutboundConsent?.let { consent ->
        AlertDialog(
            onDismissRequest = { pendingOutboundConsent = null },
            title = { Text(consent.title) },
            text = { Text(consent.description) },
            confirmButton = {
                TextButton(onClick = {
                    when (consent) {
                        OutboundConsentType.CLOUD_SPEECH -> outboundViewModel.setAllowCloudSpeech(true)
                        OutboundConsentType.REMOTE_MCP -> outboundViewModel.setAllowRemoteMcp(true)
                        OutboundConsentType.REMOTE_EMBEDDING -> outboundViewModel.setAllowRemoteEmbedding(true)
                    }
                    pendingOutboundConsent = null
                }) { Text("同意开启") }
            },
            dismissButton = {
                TextButton(onClick = { pendingOutboundConsent = null }) { Text("取消") }
            },
        )
    }
}

private enum class OutboundConsentType(val title: String, val description: String) {
    CLOUD_SPEECH(
        "允许语音识别上云？",
        "开启后，你主动录制的语音会发送给当前 AI 服务用于转写或实时对话。每次发送只记录时间、类别和大小，不保存语音原文。",
    ),
    REMOTE_MCP(
        "允许远程外部工具？",
        "开启后，只有经你当次确认的工具参数才会发送给已配置的 MCP 服务。连接服务不会获得整个联系人库或日历。",
    ),
    REMOTE_EMBEDDING(
        "允许远程语义检索？",
        "开启只为未来的远程向量服务预留授权。当前生产链路仍使用本机检索；电话、邮箱、证件号、凭据和 SENSITIVE 内容仍会强制阻断。",
    ),
}

/** 隐私页"数据发送范围"总览：每类数据是否发送给模型 + 一句话说明。 */
internal enum class DataSendScope(val dataType: String, val isSent: Boolean, val note: String) {
    CONTACT_IDENTITY("联系人资料（脱敏）", true, "脱敏后发送，用于识别和称呼"),
    DIRECT_IDENTIFIER("手机号与邮箱", false, "始终脱敏，不会原样发送"),
    MESSAGE_CONTENT("消息原文", false, "只提炼摘要，原文不发送"),
    CALL_LOG("通话记录", false, "只保存时间与方向等元数据"),
    VOICE("语音（授权后）", true, "仅在你授权后发给语音识别"),
    ;

    val statusLabel: String get() = if (isSent) "发送" else "不发送"
}

@Composable
private fun DataSendScopeRow(scope: DataSendScope) {
    Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = ZhiBanSize.ListRow).padding(vertical = ZhiBanSpacing.Md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            scope.dataType,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            scope.statusLabel,
            style = MaterialTheme.typography.labelMedium,
            color = if (scope.isSent) ZhiBanTerracotta else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
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
                    "允许知伴发送通知",
                    if (notificationsGranted) "已允许" else "未允许",
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
                Divider()
                SettingsActionRow("打开系统通知设置", onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        },
                    )
                })
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

@Composable
fun StorageSettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    var cacheBytes by remember { mutableLongStateOf(regenerableCacheSize(context.cacheDir)) }
    var confirmClear by remember { mutableStateOf(false) }
    val databaseBytes = remember { agentDatabaseSize(context) }
    SettingsPageFrame("存储", onBack) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ZhiBanSpacing.PageHorizontal)
                .padding(bottom = ZhiBanSpacing.PageBottom),
            verticalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Md),
        ) {
            SettingsCard {
                SettingsRow("知伴数据", formatBytes(databaseBytes))
                Divider()
                SettingsRow("临时文件", formatBytes(cacheBytes))
                Divider()
                SettingsActionRow("清理临时文件", onClick = { confirmClear = true }, isDanger = cacheBytes > 0)
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            shape = RoundedCornerShape(ZhiBanRadius.Dialog),
            containerColor = ZhiBanCard,
            title = { Text("清理临时文件？") },
            text = { Text("下载预览和临时处理文件会被删除，需要时可重新生成。") },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
            confirmButton = {
                Button(
                    onClick = {
                        clearRegenerableCache(context.cacheDir)
                        cacheBytes = regenerableCacheSize(context.cacheDir)
                        confirmClear = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("清理") }
            },
        )
    }
}

data class DataExportUiState(val exporting: Boolean = false, val exportedFile: File? = null, val exportFailed: Boolean = false)

@HiltViewModel
class DataExportViewModel @Inject constructor(private val exportService: AgentDataExportService) : ViewModel() {
    private val mutableState = MutableStateFlow(DataExportUiState())
    val state = mutableState.asStateFlow()

    fun export() {
        if (mutableState.value.exporting) return
        mutableState.update { it.copy(exporting = true, exportFailed = false) }
        viewModelScope.launch {
            com.zhiban.rebuild.runtime.runSuspendCatching { exportService.create() }
                .onSuccess { file -> mutableState.update { it.copy(exporting = false, exportedFile = file) } }
                .onFailure { mutableState.update { it.copy(exporting = false, exportFailed = true) } }
        }
    }

    fun exportConsumed() = mutableState.update { it.copy(exportedFile = null) }
}

@Composable
fun DataSettingsPage(onBack: () -> Unit, onMemory: () -> Unit, onRunHistory: () -> Unit, exportViewModel: DataExportViewModel = hiltViewModel()) {
    val context = LocalContext.current
    var confirmReset by remember { mutableStateOf(false) }
    val exportState by exportViewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(exportState.exportedFile) {
        exportState.exportedFile?.let { file ->
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            context.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "application/json"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                    "导出全部数据",
                ),
            )
            exportViewModel.exportConsumed()
        }
    }
    SettingsPageFrame("数据管理", onBack) {
        LazyColumn(
            Modifier.padding(horizontal = ZhiBanSpacing.PageHorizontal),
            verticalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Md),
        ) {
            item {
                Text(
                    "保存在这台手机上",
                    style = MaterialTheme.typography.labelMedium,
                    color = ZhiBanTextSecondary,
                    modifier = Modifier.padding(horizontal = ZhiBanSpacing.Xs),
                )
            }
            item {
                SettingsCard {
                    SettingsRow("对话记录", "")
                    Divider()
                    SettingsActionRow("已保存的记忆", onClick = onMemory)
                    Divider()
                    SettingsRow("联系人与关系", "")
                    Divider()
                    SettingsRow("日程", "")
                    Divider()
                    SettingsRow("消息采集记录", "")
                }
            }
            item {
                Text(
                    "诊断与导出",
                    style = MaterialTheme.typography.labelMedium,
                    color = ZhiBanTextSecondary,
                    modifier = Modifier.padding(horizontal = ZhiBanSpacing.Xs, vertical = ZhiBanSpacing.Sm),
                )
            }
            item {
                SettingsCard {
                    SettingsActionRow(
                        title = if (exportState.exporting) "正在导出…" else "导出全部数据",
                        onClick = exportViewModel::export,
                    )
                    Divider()
                    SettingsActionRow("导出诊断记录", onClick = onRunHistory)
                }
            }
            if (exportState.exportFailed) {
                item {
                    Text(
                        "导出失败，请稍后重试",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = ZhiBanSpacing.Lg),
                    )
                }
            }
            item {
                Text(
                    "重置",
                    style = MaterialTheme.typography.labelMedium,
                    color = ZhiBanTextSecondary,
                    modifier = Modifier.padding(horizontal = ZhiBanSpacing.Xs, vertical = ZhiBanSpacing.Sm),
                )
            }
            item {
                SettingsCard {
                    SettingsActionRow(
                        title = "清除这台手机上的全部知伴数据",
                        onClick = { confirmReset = true },
                        isDanger = true,
                    )
                }
            }
        }
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            shape = RoundedCornerShape(ZhiBanRadius.Dialog),
            containerColor = ZhiBanCard,
            title = { Text("重置知伴？") },
            text = {
                Text(
                    "系统会打开知伴的应用信息页。进入“存储”并选择“清除数据”后，对话、记忆、联系人关系、日程、API Key 和所有本机设置都会被删除，无法恢复。",
                )
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text("取消") }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmReset = false
                        context.openAppDetails()
                    },
                ) {
                    Text("打开系统设置", color = MaterialTheme.colorScheme.error)
                }
            },
        )
    }
}

@Composable
fun ReportErrorSettingsPage(onBack: () -> Unit, onDiagnostics: () -> Unit) {
    val context = LocalContext.current
    SettingsPageFrame("报告问题", onBack) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ZhiBanSpacing.PageHorizontal)
                .padding(bottom = ZhiBanSpacing.PageBottom),
            verticalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Md),
        ) {
            SettingsCard {
                SettingsActionRow("发送问题描述", onClick = {
                    val text = "知伴问题反馈\n版本：${BuildConfig.VERSION_NAME}\n设备：${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n\n问题描述："
                    context.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "知伴问题反馈")
                                putExtra(Intent.EXTRA_TEXT, text)
                            },
                            "选择发送方式",
                        ),
                    )
                })
                Divider()
                SettingsActionRow("导出诊断记录", onClick = onDiagnostics)
            }
        }
    }
}

internal data class AboutLegalEntry(val title: String, val body: String)

internal val ABOUT_LEGAL_ENTRIES: List<AboutLegalEntry> = listOf(
    AboutLegalEntry(
        "隐私政策",
        "知伴把对话、记忆、联系人和日程保存在这台手机的私有空间；API Key 加密存放在本机安全区域。" +
            "只有经你允许的内容才会发送给当前 AI 服务，手机号、邮箱、身份证号等发送前始终脱敏；" +
            "消息正文和通话录音不会发送。你可以在“隐私与权限”里查看数据发送范围，并随时关闭各项授权。",
    ),
    AboutLegalEntry(
        "使用条款",
        "知伴是你的个人智能助理，用于管理日程、联系人关系、记忆与场景任务。" +
            "修改、删除或对外发送内容前，知伴会先请你确认；自动记录的内容都可以撤销或纠正。" +
            "请仅在你获得授权的设备上使用，并自行确认重要操作。",
    ),
    AboutLegalEntry(
        "开源许可",
        "知伴基于以下开源项目构建，遵循各自的开源许可：\n" +
            "• Jetpack Compose（Apache-2.0）\n" +
            "• Room（Apache-2.0）\n" +
            "• Hilt（Apache-2.0）\n" +
            "• OkHttp（Apache-2.0）\n" +
            "• Retrofit（Apache-2.0）\n" +
            "• Kotlinx Coroutines / Serialization（Apache-2.0）\n" +
            "• SQLCipher for Android（BSD）\n" +
            "• ML Kit（Google 服务条款）",
    ),
)

@Composable
fun AboutZhiBanPage(onBack: () -> Unit) {
    var openEntry by remember { mutableStateOf<AboutLegalEntry?>(null) }
    SettingsPageFrame("关于知伴", onBack) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ZhiBanSpacing.PageHorizontal, vertical = ZhiBanSpacing.Xxl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(72.dp).background(ZhiBanTextPrimary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "知",
                    color = Color.White,
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(ZhiBanSpacing.Lg))
            Text("知伴", style = MaterialTheme.typography.headlineSmall, color = ZhiBanTextPrimary)
            Text(
                "版本 ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall,
                color = ZhiBanTextSecondary,
            )
            Spacer(Modifier.height(ZhiBanSpacing.Xxl))
            Spacer(Modifier.height(ZhiBanSpacing.Xxl))
            SettingsCard {
                ABOUT_LEGAL_ENTRIES.forEachIndexed { index, entry ->
                    if (index > 0) Divider()
                    SettingsActionRow(entry.title, onClick = { openEntry = entry })
                }
            }
        }
    }
    openEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { openEntry = null },
            shape = RoundedCornerShape(ZhiBanRadius.Dialog),
            containerColor = ZhiBanCard,
            title = { Text(entry.title) },
            text = { Text(entry.body) },
            confirmButton = {
                TextButton(onClick = { openEntry = null }) { Text("关闭") }
            },
        )
    }
}

@Composable
fun AppearanceSettingsPage(onBack: () -> Unit, themePreferenceStore: ThemePreferenceStore = hiltViewModel<AppearanceThemeViewModel>().store) {
    val preference by themePreferenceStore.preference.collectAsStateWithLifecycle()
    SettingsPageFrame("外观", onBack) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ZhiBanSpacing.PageHorizontal)
                .padding(bottom = ZhiBanSpacing.PageBottom),
        ) {
            SettingsCard {
                ThemeOptionRow(
                    title = "浅色",
                    subtitle = "",
                    selected = preference == ThemePreference.LIGHT,
                    onClick = { themePreferenceStore.setPreference(ThemePreference.LIGHT) },
                )
                Divider()
                ThemeOptionRow(
                    title = "深色",
                    subtitle = "",
                    selected = preference == ThemePreference.DARK,
                    onClick = { themePreferenceStore.setPreference(ThemePreference.DARK) },
                )
                Divider()
                ThemeOptionRow(
                    title = "跟随系统",
                    subtitle = "",
                    selected = preference == ThemePreference.SYSTEM,
                    onClick = { themePreferenceStore.setPreference(ThemePreference.SYSTEM) },
                )
            }
        }
    }
}

@HiltViewModel
class AppearanceThemeViewModel @Inject constructor(val store: ThemePreferenceStore) : ViewModel()

@Composable
private fun ThemeOptionRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().defaultMinSize(
            minHeight = if (subtitle.isBlank()) ZhiBanSize.ListRow else ZhiBanSize.ListRowWithSubtitle,
        ).clickable(onClick = onClick).padding(vertical = ZhiBanSpacing.Md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (selected) ZhiBanTerracotta else MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (selected) {
            Icon(Icons.Outlined.Check, contentDescription = null, tint = ZhiBanTerracotta)
        }
    }
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(ZhiBanRadius.Card))
            .padding(horizontal = ZhiBanSpacing.Lg),
        content = content,
    )
}

@Composable
private fun SettingsRow(title: String, subtitle: String, trailing: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().defaultMinSize(
            minHeight = if (subtitle.isBlank()) ZhiBanSize.ListRow else ZhiBanSize.ListRowWithSubtitle,
        ).padding(vertical = ZhiBanSpacing.Md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing?.invoke()
    }
}

@Composable
private fun SettingsToggleRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    SettingsRow(title, subtitle) {
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingsActionRow(title: String, onClick: () -> Unit, isDanger: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().defaultMinSize(
            minHeight = ZhiBanSize.ListRow,
        ).clickable(onClick = onClick).padding(vertical = ZhiBanSpacing.Md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = if (isDanger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        Icon(
            Icons.AutoMirrored.Outlined.ArrowForward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(ZhiBanSize.IconSmall),
        )
    }
}

@Composable
private fun SettingsInfoRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, text: String) {
    Row(
        Modifier.fillMaxWidth().defaultMinSize(
            minHeight = ZhiBanSize.ListRowWithSubtitle,
        ).padding(vertical = ZhiBanSpacing.Md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZhiBanLeadingIcon(icon)
        Spacer(Modifier.size(ZhiBanSpacing.Md))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SettingsPermissionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, text: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().defaultMinSize(
            minHeight = ZhiBanSize.ListRowWithSubtitle,
        ).clickable(onClick = onClick).padding(vertical = ZhiBanSpacing.Md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZhiBanLeadingIcon(icon)
        Spacer(Modifier.size(ZhiBanSpacing.Md))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(
            Icons.AutoMirrored.Outlined.ArrowForward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(ZhiBanSize.IconSmall),
        )
    }
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
}

private data class PermissionStatus(val isGranted: Boolean) {
    val label: String get() = if (isGranted) "已允许" else "未允许"
}

private data class PermissionSnapshot(
    val microphone: PermissionStatus,
    val camera: PermissionStatus,
    val contacts: PermissionStatus,
    val calendar: PermissionStatus,
    val callLog: PermissionStatus,
    val isNotificationListener: Boolean,
    val outgoingAccessibility: Boolean,
) {
    companion object {
        val Defaults = PermissionSnapshot(
            microphone = PermissionStatus(false),
            camera = PermissionStatus(false),
            contacts = PermissionStatus(false),
            calendar = PermissionStatus(false),
            callLog = PermissionStatus(false),
            isNotificationListener = false,
            outgoingAccessibility = false,
        )

        fun read(context: android.content.Context): PermissionSnapshot = PermissionSnapshot(
            microphone = context.permissionStatus(Manifest.permission.RECORD_AUDIO),
            camera = context.permissionStatus(Manifest.permission.CAMERA),
            contacts = context.permissionStatus(Manifest.permission.READ_CONTACTS),
            calendar = context.permissionStatus(Manifest.permission.READ_CALENDAR),
            callLog = context.permissionStatus(Manifest.permission.READ_CALL_LOG),
            isNotificationListener = NotificationManagerCompat.getEnabledListenerPackages(context)
                .contains(context.packageName),
            outgoingAccessibility = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ).orEmpty().split(':').any {
                ComponentName.unflattenFromString(it) ==
                    ComponentName(context, OutgoingMessageAccessibilityService::class.java)
            },
        )
    }
}

private fun android.content.Context.permissionStatus(permission: String): PermissionStatus =
    PermissionStatus(ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED)

private fun android.content.Context.openAppDetails() {
    startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
        },
    )
}

@Composable
private fun RefreshPermissionsOnResume(onResume: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) onResume()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

private fun directorySize(file: File): Long = runCatching {
    if (file.isFile) file.length() else file.listFiles()?.sumOf(::directorySize) ?: 0L
}.getOrDefault(0L)

internal fun regenerableCacheSize(cacheDirectory: File): Long = cacheDirectory.listFiles().orEmpty()
    .filterNot { it.name in PROTECTED_CACHE_DIRECTORIES }
    .sumOf(::directorySize)

internal fun clearRegenerableCache(cacheDirectory: File) {
    cacheDirectory.listFiles().orEmpty()
        .filterNot { it.name in PROTECTED_CACHE_DIRECTORIES }
        .forEach(::deleteRecursivelySafe)
}

private fun deleteRecursivelySafe(file: File) {
    if (file.isDirectory) file.listFiles()?.forEach(::deleteRecursivelySafe)
    runCatching { file.delete() }
}

private val PROTECTED_CACHE_DIRECTORIES = setOf("zhiban-runtime-input", "multimodal")

internal fun agentDatabaseSize(context: android.content.Context): Long = runCatching {
    context.getDatabasePath(AGENT_DATABASE_FILE_NAME).length()
}.getOrDefault(0L)

private fun formatBytes(bytes: Long): String = when {
    bytes < 1_024 -> "$bytes B"
    bytes < 1_048_576 -> "${bytes / 1_024} KB"
    else -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1_048_576.0)
}
