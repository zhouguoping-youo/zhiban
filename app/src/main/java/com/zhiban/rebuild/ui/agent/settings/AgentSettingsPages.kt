package com.zhiban.rebuild.ui.agent.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Rule
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.zhiban.rebuild.runtime.config.AgentControlStore
import com.zhiban.rebuild.runtime.config.ExecutionPreference
import com.zhiban.rebuild.runtime.config.FeedbackPolicy
import com.zhiban.rebuild.runtime.config.MemoryPolicy
import com.zhiban.rebuild.runtime.mcp.McpRemoteEnvironment
import com.zhiban.rebuild.runtime.mcp.McpRemoteServer
import com.zhiban.rebuild.runtime.mcp.McpRemoteTool
import com.zhiban.rebuild.runtime.memory.AgentMemoryItem
import com.zhiban.rebuild.runtime.memory.AgentMemorySettingsService
import com.zhiban.rebuild.runtime.personalization.AgentPersonalizationStore
import com.zhiban.rebuild.runtime.personalization.Personalization
import com.zhiban.rebuild.runtime.personalization.ResponseStyle
import com.zhiban.rebuild.runtime.personalization.UserProfile
import com.zhiban.rebuild.runtime.personalization.UserProfileStore
import com.zhiban.rebuild.runtime.provider.ProviderEnvironmentManager
import com.zhiban.rebuild.runtime.runSuspendCatching
import com.zhiban.rebuild.runtime.tool.RuntimeToolCatalog
import com.zhiban.rebuild.ui.components.ZhiBanAlertDialog
import com.zhiban.rebuild.ui.components.ZhiBanChip
import com.zhiban.rebuild.ui.components.ZhiBanGlassCard
import com.zhiban.rebuild.ui.components.ZhiBanLeadingIcon
import com.zhiban.rebuild.ui.components.ZhiBanPage
import com.zhiban.rebuild.ui.components.ZhiBanSectionTitle
import com.zhiban.rebuild.ui.components.ZhiBanSwitch
import com.zhiban.rebuild.ui.components.ZhiBanToggleRow
import com.zhiban.rebuild.ui.components.ZhiBanTopBar
import com.zhiban.rebuild.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val AgentSettingsSecondary = ZhiBanTextSecondary

@Composable
internal fun AgentHeader(title: String, onBack: () -> Unit) {
    ZhiBanTopBar(title = title, onBack = onBack)
}

@Composable
fun AgentSettingsPage(
    onBack: () -> Unit,
    onPersonalization: () -> Unit,
    onMemory: () -> Unit,
    onModel: () -> Unit,
    onTools: () -> Unit,
    onSkills: () -> Unit,
    onFeedback: () -> Unit,
    onRunHistory: () -> Unit,
    viewModel: AgentSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refresh() }
    ZhiBanPage {
        Column(Modifier.fillMaxSize()) {
            AgentHeader("智能体设置", onBack)
            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = ZhiBanSpacing.PageHorizontal),
                contentPadding = PaddingValues(bottom = ZhiBanSpacing.PageBottom),
                verticalArrangement = Arrangement.spacedBy(ZhiBanSpacing.ContentGap),
            ) {
                item {
                    AgentSettingsGroup(
                        listOf(
                            AgentSettingsEntry(
                                Icons.Outlined.CloudQueue,
                                "大模型连接",
                                if (state.providerConfigured) "阶跃星辰 · 已连接" else "输入 API Key",
                                onModel,
                            ),
                            AgentSettingsEntry(
                                Icons.Outlined.Psychology,
                                "记忆",
                                if (state.memoryCount == 0) "还没有保存任何记忆" else "已保存 ${state.memoryCount} 条记忆",
                                onMemory,
                            ),
                            AgentSettingsEntry(Icons.Outlined.Tune, "回答偏好", state.answerPreferenceSummary, onPersonalization),
                            AgentSettingsEntry(Icons.Outlined.Construction, "工具", "${state.toolCount} 项可用", onTools),
                            AgentSettingsEntry(Icons.Outlined.AutoAwesome, "技能", "${state.skillCount} 项可用", onSkills),
                        ),
                    )
                }
                item {
                    AgentSettingsGroup(
                        listOf(
                            AgentSettingsEntry(Icons.Outlined.RateReview, "回答反馈", "点赞、点踩与改进建议", onFeedback),
                            AgentSettingsEntry(Icons.Outlined.History, "运行记录", "", onRunHistory),
                        ),
                    )
                }
            }
        }
    }
}

private data class AgentSettingsEntry(val icon: ImageVector, val title: String, val subtitle: String, val onClick: () -> Unit)

@Composable
private fun AgentSettingsGroup(entries: List<AgentSettingsEntry>) {
    ZhiBanGlassCard(Modifier.fillMaxWidth(), cornerRadius = ZhiBanRadius.Card) {
        entries.forEachIndexed { index, entry ->
            SettingRowContent(entry.icon, entry.title, entry.subtitle, entry.onClick)
            if (index < entries.lastIndex) {
                HorizontalDivider(
                    modifier = Modifier.padding(start = 72.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
        }
    }
}

@Composable internal fun SettingRow(icon: ImageVector, title: String, subtitle: String, onClick: (() -> Unit)? = null) {
    ZhiBanGlassCard(Modifier.fillMaxWidth(), cornerRadius = ZhiBanRadius.Card) {
        SettingRowContent(icon, title, subtitle, onClick)
    }
}

@Composable
private fun SettingRowContent(icon: ImageVector, title: String, subtitle: String, onClick: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = onClick != null) { onClick?.invoke() }
            .defaultMinSize(minHeight = if (subtitle.isBlank()) ZhiBanSize.ListRow else ZhiBanSize.ListRowWithSubtitle)
            .padding(horizontal = ZhiBanSpacing.Lg, vertical = ZhiBanSpacing.Md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZhiBanLeadingIcon(icon)
        Spacer(Modifier.width(ZhiBanSpacing.Md))
        Column(Modifier.weight(1f)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            if (subtitle.isNotBlank()) {
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (onClick != null) {
            Icon(Icons.Outlined.ChevronRight, null, tint = AgentSettingsSecondary, modifier = Modifier.size(ZhiBanIconSize.Inline))
        }
    }
}

data class AgentSettingsState(
    val answerPreferenceSummary: String = "平衡 · 标准",
    val memoryCount: Int = 0,
    val providerConfigured: Boolean = false,
    val toolCount: Int = 0,
    val skillCount: Int = 0,
)

@HiltViewModel class AgentSettingsViewModel @Inject constructor(
    private val prefs: AgentPersonalizationStore,
    private val controls: AgentControlStore,
    private val memory: AgentMemorySettingsService,
    private val provider: ProviderEnvironmentManager,
    private val mcp: McpRemoteEnvironment,
) : ViewModel() {
    private val _state = MutableStateFlow(AgentSettingsState())
    val state = _state.asStateFlow()
    init {
        refresh()
    }
    fun refresh() {
        viewModelScope.launch {
            _state.value =
                AgentSettingsState(
                    "${prefs.load().style.label} · ${controls.execution().runtimeLevel}",
                    memory.list().size,
                    provider.isConfigured(),
                    RuntimeToolCatalog.production().names().size + mcp.tools().size,
                    com.zhiban.agent.skills.BuiltInSkills.all.size,
                )
        }
    }
}

data class MemoryUiState(
    val items: List<AgentMemoryItem> = emptyList(),
    val isLoading: Boolean = true,
    val busy: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val policy: MemoryPolicy = MemoryPolicy(),
)

@HiltViewModel class AgentToolsViewModel @Inject constructor(private val controls: AgentControlStore, private val mcp: McpRemoteEnvironment) : ViewModel() {
    private val names get() = RuntimeToolCatalog.production().names() + mcp.tools().map { it.canonicalName }
    private val _state = MutableStateFlow(snapshot())
    val state = _state.asStateFlow()
    private fun snapshot(busy: Boolean = false, message: String? = null) =
        AgentToolsState(names.associateWith(controls::isToolEnabled), mcp.servers(), mcp.tools(), busy, message)
    fun enabled(name: String, value: Boolean) {
        controls.saveToolEnabled(name, value)
        _state.update {
            it.copy(
                enabled =
                    it.enabled + (name to value),
            )
        }
    }
    fun serverEnabled(id: String, value: Boolean) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null) }
            runSuspendCatching { mcp.setEnabled(id, value) }.onSuccess {
                _state.value =
                    snapshot()
            }.onFailure { _state.value = snapshot(message = "设置失败，请重试") }
        }
    }
    fun removeServer(id: String) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null) }
            runSuspendCatching { mcp.remove(id) }.onSuccess {
                _state.value =
                    snapshot(message = "外部服务已移除")
            }.onFailure { _state.value = snapshot(message = "移除失败，请重试") }
        }
    }
    fun health(id: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = null)
            val h = mcp.healthCheck(id)
            _state.value =
                snapshot(message = if (h.available) "连接正常" else "连接失败，请检查服务设置")
        }
    }
    fun configure(id: String, name: String, endpoint: String, token: String) {
        viewModelScope.launch {
            _state.value =
                _state.value.copy(busy = true, message = null)
            runSuspendCatching {
                mcp.configure(id.trim(), name.trim(), endpoint.trim(), token.takeIf(String::isNotBlank)?.toByteArray())
            }.onSuccess {
                _state.value =
                    snapshot(message = "已连接，可使用 ${it.server?.toolCount ?: 0} 个工具")
            }.onFailure {
                _state.value =
                    snapshot(message = "连接失败，请检查服务设置")
            }
        }
    }
}

@Composable fun AgentToolsPage(onBack: () -> Unit, vm: AgentToolsViewModel = hiltViewModel()) {
    val tools = RuntimeToolCatalog.production().specs.values.sortedBy { it.name }
    val state by vm.state.collectAsStateWithLifecycle()
    var adding by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var pendingRemoval by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<McpRemoteServer?>(null)
    }
    ZhiBanPage {
        Column(Modifier.fillMaxSize()) {
            AgentHeader("工具", onBack)
            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = ZhiBanSpacing.PageHorizontal),
                contentPadding = PaddingValues(bottom = ZhiBanSpacing.PageBottom),
                verticalArrangement = Arrangement.spacedBy(ZhiBanSpacing.ContentGap),
            ) {
                item {
                    ZhiBanSectionTitle(
                        title = "外部服务",
                        action = "添加",
                        onActionClick = { adding = true },
                    )
                }
                state.message?.let {
                    item {
                        Text(
                            it,
                            color = if (it.startsWith(
                                    "连接失败",
                                )
                            ) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = ZhiBanSpacing.Xs),
                        )
                    }
                }
                if (state.remoteServers.isEmpty()) {
                    item {
                        SettingRow(
                            Icons.Outlined.CloudQueue,
                            "未连接外部服务",
                            "连接后可使用更多工具",
                        )
                    }
                }
                items(state.remoteServers, key = { "server-${it.id}" }) { server ->
                    ZhiBanGlassCard(Modifier.fillMaxWidth(), cornerRadius = ZhiBanRadius.Card) {
                        Column(
                            Modifier.padding(ZhiBanSpacing.Lg),
                            verticalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Sm),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        server.displayName,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    Text(
                                        "${server.toolCount} 个工具",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                ZhiBanSwitch(
                                    checked = server.enabled,
                                    onCheckedChange = { vm.serverEnabled(server.id, it) },
                                    enabled = !state.busy,
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Sm)) {
                                TextButton(onClick = { vm.health(server.id) }, enabled = !state.busy) { Text("检测连接") }
                                TextButton(onClick = { pendingRemoval = server }, enabled = !state.busy) {
                                    Text("移除", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
                if (state.remoteTools.isNotEmpty()) {
                    item { ZhiBanSectionTitle("外部工具") }
                }
                items(state.remoteTools, key = { it.canonicalName }) { tool ->
                    ToggleRow(
                        tool.remoteName,
                        "外部服务 · 使用前确认",
                        state.enabled[tool.canonicalName] != false,
                    ) { vm.enabled(tool.canonicalName, it) }
                }
                item { ZhiBanSectionTitle("内置工具") }
                items(tools, key = { it.name }) { tool ->
                    ToggleRow(
                        toolDisplayName(tool.name),
                        if (tool.risk.name == "READ_ONLY") "只读" else "修改前确认",
                        state.enabled[tool.name] != false,
                    ) { vm.enabled(tool.name, it) }
                }
            }
        }
    }
    if (adding) {
        McpAddDialog(onDismiss = { adding = false }, onConnect = { id, name, endpoint, token ->
            vm.configure(id, name, endpoint, token)
            adding =
                false
        }, busy = state.busy)
    }
    pendingRemoval?.let { server ->
        ZhiBanAlertDialog(
            onDismissRequest = { pendingRemoval = null },
            shape = RoundedCornerShape(ZhiBanRadius.Dialog),
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            title = { Text("移除“${server.displayName}”？") },
            text = { Text("知伴将无法再使用这个服务提供的 ${server.toolCount} 个工具，已保存的令牌也会从本机删除。") },
            dismissButton = { TextButton(onClick = { pendingRemoval = null }) { Text("取消") } },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingRemoval = null
                        vm.removeServer(server.id)
                    },
                ) {
                    Text("移除", color = MaterialTheme.colorScheme.error)
                }
            },
        )
    }
}

@Composable private fun McpAddDialog(onDismiss: () -> Unit, onConnect: (String, String, String, String) -> Unit, busy: Boolean) {
    var id by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var name by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var endpoint by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var token by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    ZhiBanAlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(
            ZhiBanRadius.Dialog,
        ),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        title = {
            Text("添加外部 MCP 服务")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Md)) {
                Text(
                    "仅支持 HTTPS。外部工具默认在执行前请你确认。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(id, {
                    id =
                        it
                }, label = { Text("服务 ID，如 feishu") }, singleLine = true)
                OutlinedTextField(name, { name = it }, label = { Text("显示名称") }, singleLine = true)
                OutlinedTextField(endpoint, {
                    endpoint =
                        it
                }, label = { Text("服务地址") }, singleLine = true)
                OutlinedTextField(token, {
                    token = it
                }, label = {
                    Text("访问令牌（可选）")
                }, singleLine = true, visualTransformation = PasswordVisualTransformation())
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
            ) {
                Text("取消")
            }
        },
        confirmButton = {
            Button(
                onClick = { onConnect(id, name, endpoint, token) },
                enabled =
                    !busy && id.isNotBlank() && name.isNotBlank() && endpoint.isNotBlank(),
            ) { Text("连接") }
        },
    )
}
private fun toolDisplayName(name: String) = when (name) {
    "calendar.schedule.create" -> "创建日程"
    "calendar.schedule.search" -> "查询日程"
    "calendar.schedule.conflicts" -> "检查日程冲突"
    "calendar.schedule.update" -> "修改日程"
    "calendar.schedule.delete" -> "删除日程"
    "contact.createCandidate" -> "新建联系人候选"
    "contact.getDetail" -> "查看联系人详情"
    "contact.search" -> "搜索联系人"
    "contact.maintenance.list" -> "检查联系人"
    "contact.identity.resolve" -> "关联社交身份"
    "contact.tag.add" -> "自动补充联系人标签"
    "memory.remember" -> "保存长期记忆"
    "memory.upsert" -> "自动更新长期记忆"
    "memory.search" -> "搜索长期记忆"
    "memory.delete" -> "删除长期记忆"
    "relationship.createCandidate" -> "建立联系人关系"
    "relationship.getEvidence" -> "查看关系证据"
    "relationship.search" -> "查询联系人关系"
    "communication.message.compose" -> "准备并打开消息"
    else -> name
}

@Composable private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    ZhiBanGlassCard(Modifier.fillMaxWidth(), cornerRadius = ZhiBanRadius.Card) {
        ZhiBanToggleRow(title, subtitle, checked, onChecked)
    }
}

internal fun executionHint(preference: ExecutionPreference): String = when (preference) {
    ExecutionPreference.FAST -> "简单问答 · 检索更少，响应更快"
    ExecutionPreference.BALANCED -> "日常任务 · 自动平衡速度与信息量"
    ExecutionPreference.DEEP -> "复杂问题 · 检索更多上下文，耗时更长"
}

data class FeedbackState(
    val policy: FeedbackPolicy = FeedbackPolicy(),
    val suggestion: com.zhiban.rebuild.runtime.config.PreferenceImprovementSuggestion? = null,
)

@HiltViewModel
class AgentFeedbackViewModel @Inject constructor(private val controls: AgentControlStore, private val personalization: AgentPersonalizationStore) :
    ViewModel() {
    private val _state = MutableStateFlow(FeedbackState(controls.feedback(), controls.pendingImprovement()))
    val state = _state.asStateFlow()
    fun human(v: Boolean) {
        save(_state.value.policy.copy(useHumanFeedback = v))
    }
    fun improve(v: Boolean) {
        save(_state.value.policy.copy(allowPreferenceImprovement = v))
        if (!v) reject()
    }
    fun accept() {
        val current = personalization.load()
        personalization.save(current.copy(style = ResponseStyle.CONCISE))
        controls.dismissImprovement()
        _state.update { it.copy(suggestion = null) }
    }
    fun reject() {
        controls.dismissImprovement()
        _state.update { it.copy(suggestion = null) }
    }
    private fun save(v: FeedbackPolicy) {
        controls.saveFeedback(v)
        _state.value =
            FeedbackState(v, controls.pendingImprovement())
    }
}

@Composable fun AgentFeedbackImprovementPage(onBack: () -> Unit, vm: AgentFeedbackViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    ZhiBanPage {
        Column(Modifier.fillMaxSize()) {
            AgentHeader("回答反馈", onBack)
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ToggleRow("显示点赞和点踩", "出现在知伴回答下方", s.policy.useHumanFeedback, vm::human)
                ToggleRow("根据反馈提出改进", "修改前仍会询问你", s.policy.allowPreferenceImprovement, vm::improve)
                s.suggestion?.let { suggestion ->
                    ZhiBanGlassCard(Modifier.fillMaxWidth(), cornerRadius = ZhiBanRadius.Card) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("待审核建议", color = ZhiBanTerracotta, fontWeight = FontWeight.SemiBold)
                            Text(suggestion.title, fontWeight = FontWeight.SemiBold)
                            Text(
                                suggestion.description,
                                color = ZhiBanTextSecondary,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedButton(vm::reject, Modifier.weight(1f)) { Text("不采用") }
                                Button(vm::accept, Modifier.weight(1f)) { Text("接受建议") }
                            }
                        }
                    }
                }
            }
        }
    }
}

data class AgentRunHistoryState(
    val items: List<com.zhiban.rebuild.runtime.observability.AgentRunTrace> = emptyList(),
    val metrics: com.zhiban.rebuild.runtime.observability.AgentMetricsSummary? = null,
    val isLoading: Boolean = true,
    val exporting: Boolean = false,
    val exportedFile: java.io.File? = null,
    val exportError: Boolean = false,
    val filter: RunHistoryFilter = RunHistoryFilter.ALL,
)

@HiltViewModel class AgentRunHistoryViewModel @Inject constructor(
    private val traces: com.zhiban.rebuild.runtime.observability.AgentTraceService,
    private val diagnostics: com.zhiban.rebuild.runtime.observability.AgentDiagnosticBundleService,
) : ViewModel() {
    private val _state = MutableStateFlow(AgentRunHistoryState())
    val state = _state.asStateFlow()
    init {
        viewModelScope.launch { _state.value = AgentRunHistoryState(traces.recent(), traces.metrics(), false) }
    }
    fun selectFilter(filter: RunHistoryFilter) = _state.update { it.copy(filter = filter) }
    fun export() {
        if (_state.value.exporting) return
        _state.update { it.copy(exporting = true, exportError = false) }
        viewModelScope.launch {
            runSuspendCatching {
                diagnostics.create()
            }.onSuccess { file ->
                _state.update { it.copy(exporting = false, exportedFile = file) }
            }.onFailure { _state.update { it.copy(exporting = false, exportError = true) } }
        }
    }
    fun exportConsumed() {
        _state.update { it.copy(exportedFile = null) }
    }
}

@Composable fun AgentRunHistoryPage(onBack: () -> Unit, vm: AgentRunHistoryViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(s.exportedFile) {
        s.exportedFile?.let { file ->
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            context.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "application/json"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                    "导出脱敏诊断包",
                ),
            )
            vm.exportConsumed()
        }
    }
    ZhiBanPage {
        Column(Modifier.fillMaxSize()) {
            AgentHeader("运行记录", onBack)
            LazyColumn(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    OutlinedButton(vm::export, Modifier.fillMaxWidth(), enabled = !s.exporting) {
                        Icon(Icons.Outlined.IosShare, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (s.exporting) "正在生成…" else "导出脱敏诊断包")
                    }
                }
                if (s.exportError) {
                    item {
                        Text(
                            "诊断包生成失败，请稍后重试",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                s.metrics?.takeIf { it.sampledRuns > 0 }?.let { metrics ->
                    item {
                        ZhiBanGlassCard(Modifier.fillMaxWidth(), cornerRadius = 18.dp) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("本机运行概览", fontWeight = FontWeight.SemiBold)
                                Text(
                                    "最近 ${metrics.sampledRuns} 次 · 成功率 ${metrics.successRatePercent}% · 平均 ${metrics.averageDurationMs}ms",
                                    color = ZhiBanTextSecondary,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    "工具执行 ${metrics.toolExecutionCount} 次 · 安全降级率 ${metrics.degradationRatePercent}%",
                                    color = ZhiBanTextSecondary,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                val latency = listOfNotNull(
                                    metrics.firstTokenP95Ms?.let { "首字 p95 ${it}ms" },
                                    metrics.retrievalP95Ms?.let { "检索 p95 ${it}ms" },
                                    metrics.averageToolDurationMs?.let { "工具平均 ${it}ms" },
                                )
                                if (latency.isNotEmpty()) {
                                    Text(
                                        latency.joinToString(" · "),
                                        color = ZhiBanTextSecondary,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                Text(
                                    "仅统计状态与耗时，不读取或展示对话内容",
                                    color = ZhiBanTerracotta,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                }
                when {
                    s.isLoading -> item { CircularProgressIndicator() }

                    s.items.isEmpty() -> item {
                        Text("暂无运行记录", color = ZhiBanTextSecondary, modifier = Modifier.padding(16.dp))
                    }

                    else -> {
                        item {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                RunHistoryFilter.entries.forEach { filter ->
                                    ZhiBanChip(
                                        text = filter.label,
                                        selected = s.filter == filter,
                                        modifier = Modifier.weight(1f),
                                        onClick = { vm.selectFilter(filter) },
                                    )
                                }
                            }
                        }
                        val visible = s.items.filter { s.filter.matches(it) }
                        if (visible.isEmpty()) {
                            item {
                                Text("没有「${s.filter.label}」的记录", color = ZhiBanTextSecondary, modifier = Modifier.padding(16.dp))
                            }
                        }
                        items(visible, key = { it.runId }) { trace ->
                            RunTraceCard(trace)
                        }
                    }
                }
            }
        }
    }
}

private fun auditPhaseLabel(phase: String) = when (phase) {
    "PERCEPTION" -> "感知"
    "MEMORY" -> "记忆"
    "PLANNING" -> "规划"
    "APPROVAL" -> "确认"
    "EXECUTION" -> "执行"
    "FEEDBACK" -> "反馈"
    else -> phase
}
private fun auditStatusLabel(status: String) = when (status.uppercase()) {
    "COMPLETED", "SUCCEEDED" -> "完成"
    "STARTED", "RUNNING" -> "进行中"
    "REQUIRED", "PENDING_APPROVAL" -> "待确认"
    "FAILED" -> "失败"
    "CANCELLED" -> "已取消"
    else -> status
}

enum class RunHistoryFilter(val label: String) {
    ALL("全部"),
    SUCCESS("成功"),
    FAILURE("失败"),
    DEGRADED("降级"),
    TOOL("工具"),
    ;

    fun matches(trace: com.zhiban.rebuild.runtime.observability.AgentRunTrace): Boolean = when (this) {
        ALL -> true
        SUCCESS -> trace.status == "SUCCEEDED"
        FAILURE -> trace.status.startsWith("FAILED")
        DEGRADED -> trace.degradationPaths.isNotEmpty()
        TOOL -> trace.toolNames.isNotEmpty()
    }
}

private data class RunStatusStyle(val label: String, val color: Color)

@Composable
private fun runStatusStyle(status: String): RunStatusStyle = when {
    status == "SUCCEEDED" -> RunStatusStyle("成功", SuccessText)
    status.startsWith("FAILED") -> RunStatusStyle("失败", MaterialTheme.colorScheme.error)
    status.startsWith("CANCELLED") -> RunStatusStyle("已取消", ZhiBanTextSecondary)
    else -> RunStatusStyle(status, ZhiBanTerracotta)
}

private fun relativeTime(epochMs: Long): String {
    if (epochMs <= 0L) return ""
    val delta = System.currentTimeMillis() - epochMs
    val minutes = delta / 60_000
    val hours = delta / 3_600_000
    val days = delta / 86_400_000
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "$minutes 分钟前"
        hours < 24 -> "$hours 小时前"
        days < 7 -> "$days 天前"
        else -> java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(epochMs))
    }
}

@Composable
private fun RunTraceCard(trace: com.zhiban.rebuild.runtime.observability.AgentRunTrace) {
    var expanded by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val status = runStatusStyle(trace.status)
    ZhiBanGlassCard(Modifier.fillMaxWidth().clickable { expanded = !expanded }, cornerRadius = 18.dp) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = status.color.copy(alpha = .12f),
                    shape = RoundedCornerShape(ZhiBanRadius.ExtraSmall),
                ) {
                    Text(
                        status.label,
                        color = status.color,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "${trace.durationMs}ms · ${trace.attemptCount} 次尝试",
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (trace.toolNames.isNotEmpty()) {
                        Text(
                            trace.toolNames.joinToString("、") { toolDisplayName(it) },
                            color = ZhiBanTextSecondary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Text(
                    relativeTime(trace.startedAtEpochMs),
                    color = ZhiBanTextSecondary,
                    style = MaterialTheme.typography.labelSmall,
                )
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = ZhiBanTextSecondary,
                    modifier = Modifier.size(ZhiBanIconSize.Inline),
                )
            }
            if (expanded) {
                trace.auditSteps.forEach { step ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            auditPhaseLabel(step.phase),
                            color = ZhiBanTerracotta,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.width(54.dp),
                        )
                        Text(
                            step.label,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        step.toolName?.let {
                            Text(
                                toolDisplayName(it),
                                color = ZhiBanTextSecondary,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            auditStatusLabel(step.status),
                            color = ZhiBanTextSecondary,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                trace.degradationPaths.takeIf { it.isNotEmpty() }?.let {
                    Text(
                        "已安全降级：${it.joinToString()}",
                        color = ZhiBanTextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
