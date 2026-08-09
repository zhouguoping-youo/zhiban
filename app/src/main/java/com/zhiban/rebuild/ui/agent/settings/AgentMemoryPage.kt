package com.zhiban.rebuild.ui.agent.settings

import android.content.Intent
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
import com.zhiban.rebuild.ui.components.ZhiBanGlassCard
import com.zhiban.rebuild.ui.components.ZhiBanLeadingIcon
import com.zhiban.rebuild.ui.components.ZhiBanPage
import com.zhiban.rebuild.ui.components.ZhiBanSectionTitle
import com.zhiban.rebuild.ui.components.ZhiBanTopBar
import com.zhiban.rebuild.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class AgentMemoryViewModel @Inject constructor(private val service: AgentMemorySettingsService, private val controls: AgentControlStore) : ViewModel() {
    private val _state = MutableStateFlow(MemoryUiState(policy = controls.memory()))
    val state = _state.asStateFlow()
    init {
        reload()
    }
    fun reload() {
        viewModelScope.launch {
            runSuspendCatching {
                service.list()
            }.onSuccess { items ->
                _state.update { it.copy(items = items, isLoading = false, busy = false, error = null) }
            }.onFailure { _state.update { it.copy(isLoading = false, busy = false, error = "记忆加载失败") } }
        }
    }
    private fun policy(value: MemoryPolicy) {
        controls.saveMemory(value)
        _state.update { it.copy(policy = value) }
    }
    fun session(v: Boolean) = policy(_state.value.policy.copy(sessionMemoryEnabled = v))
    fun longTerm(v: Boolean) = policy(_state.value.policy.copy(longTermMemoryEnabled = v))
    fun learn(v: Boolean) = policy(_state.value.policy.copy(learnFromConversations = v))
    fun temporary(v: Boolean) = policy(_state.value.policy.copy(temporaryModeEnabled = v))
    fun add(text: String, type: String) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null) }
            runSuspendCatching { service.add(text, type) }.onSuccess {
                _state.update { it.copy(message = "知伴已经记住了") }
                reload()
            }.onFailure { _state.update { it.copy(busy = false, error = "没有记住，请稍后再试") } }
        }
    }
    fun update(item: AgentMemoryItem, text: String, type: String) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null) }
            runSuspendCatching { service.update(item, text, type) }.onSuccess {
                _state.update { it.copy(message = "记忆已更新") }
                reload()
            }.onFailure { _state.update { it.copy(busy = false, error = "更新失败") } }
        }
    }
    fun delete(id: String) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            runSuspendCatching {
                service.delete(id)
            }.onSuccess { reload() }.onFailure { _state.update { it.copy(busy = false, error = "删除失败") } }
        }
    }
    fun clear() {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            runSuspendCatching { service.clear() }.onSuccess {
                _state.update { it.copy(message = "知伴已经全部忘掉了") }
                reload()
            }.onFailure { _state.update { it.copy(busy = false, error = "操作失败，请稍后再试") } }
        }
    }
}

@Composable
fun AgentMemoryPage(onBack: () -> Unit, viewModel: AgentMemoryViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    var editing by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<AgentMemoryItem?>(null) }
    var adding by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var clearing by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    ZhiBanPage {
        Column(Modifier.fillMaxSize()) {
            AgentHeader("记忆", "管理知伴如何保存和使用记忆", onBack)
            LazyColumn(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Text(
                        "允许知伴根据你的聊天和已连接的应用，为你提供更符合个人情况的回答。",
                        color = ZhiBanTextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                    )
                }
                item {
                    ZhiBanGlassCard(Modifier.fillMaxWidth(), cornerRadius = ZhiBanRadius.Card) {
                        Column {
                            CompactMemoryToggle(
                                "记住对话内容",
                                "跨对话记住对你重要的事",
                                s.policy.longTermMemoryEnabled,
                                viewModel::longTerm,
                            )
                            HorizontalDivider(
                                Modifier.padding(horizontal = 16.dp),
                                color = ZhiBanTextSecondary.copy(alpha = .12f),
                            )
                            CompactMemoryToggle(
                                "自动发现新记忆",
                                "从对话里自动提炼有用信息",
                                s.policy.learnFromConversations,
                                viewModel::learn,
                            )
                        }
                    }
                }
                item {
                    var advanced by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
                    ZhiBanGlassCard(Modifier.fillMaxWidth(), cornerRadius = ZhiBanRadius.Card) {
                        Column {
                            Row(
                                Modifier.fillMaxWidth().clickable { advanced = !advanced }
                                    .padding(horizontal = 16.dp, vertical = 13.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "高级",
                                    fontWeight = FontWeight.SemiBold,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f),
                                )
                                Icon(
                                    if (advanced) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                    contentDescription = if (advanced) "收起" else "展开",
                                    tint = ZhiBanTextSecondary,
                                )
                            }
                            if (advanced) {
                                HorizontalDivider(
                                    Modifier.padding(horizontal = 16.dp),
                                    color = ZhiBanTextSecondary.copy(alpha = .12f),
                                )
                                CompactMemoryToggle(
                                    "临时对话",
                                    "这轮对话不读也不存记忆，聊完就忘",
                                    s.policy.temporaryModeEnabled,
                                    viewModel::temporary,
                                )
                            }
                        }
                    }
                }
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "已保存的记忆",
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                if (s.items.isEmpty()) "还没有保存的记忆" else "共 ${s.items.size} 条，可查看、修改或删除",
                                color = ZhiBanTextSecondary,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        FilledTonalButton(
                            onClick = {
                                adding = true
                            },
                            enabled = !s.busy,
                            contentPadding = PaddingValues(
                                horizontal = 14.dp,
                                vertical = 8.dp,
                            ),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = ZhiBanTerracotta.copy(alpha = .13f),
                                contentColor = ZhiBanTerracotta,
                            ),
                        ) {
                            Icon(Icons.Outlined.Add, null, Modifier.size(ZhiBanIconSize.Inline))
                            Spacer(Modifier.width(3.dp))
                            Text("添加")
                        }
                    }
                }
                s.message?.let {
                    item { Text(it, color = ZhiBanTerracotta, style = MaterialTheme.typography.bodySmall) }
                }
                s.error?.let {
                    item {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
                when {
                    s.isLoading -> item { CircularProgressIndicator() }

                    s.items.isEmpty() -> item {
                        ZhiBanGlassCard(Modifier.fillMaxWidth(), cornerRadius = ZhiBanRadius.Card) {
                            Column(
                                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(
                                    Icons.Outlined.Psychology,
                                    null,
                                    tint = ZhiBanTerracotta.copy(alpha = .72f),
                                    modifier = Modifier.size(ZhiBanIconSize.Action),
                                )
                                Text("还没有记住任何事", fontWeight = FontWeight.SemiBold)
                                Text(
                                    "聊天时说“记住……”，或点上方添加",
                                    color = ZhiBanTextSecondary,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }

                    else -> items(s.items, key = { it.id }) { m ->
                        SettingRow(memoryIcon(m.type), m.text, "${m.categoryLabel} · 点击管理") {
                            editing =
                                m
                        }
                    }
                }
                if (s.items.isNotEmpty()) {
                    item {
                        TextButton(onClick = {
                            clearing = true
                        }, enabled = !s.busy, modifier = Modifier.fillMaxWidth()) {
                            Text("让知伴忘掉全部", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
    editing?.let { memory ->
        EditMemoryDialog(memory, s.busy, onDismiss = { editing = null }, onSave = { text, type ->
            viewModel.update(memory, text, type)
            editing =
                null
        }, onDelete = {
            viewModel.delete(memory.id)
            editing = null
        })
    }
    if (adding) {
        AddMemoryDialog(busy = s.busy, onDismiss = { adding = false }, onSave = { text, type ->
            viewModel.add(text, type)
            adding =
                false
        })
    }
    if (clearing) {
        AlertDialog(
            onDismissRequest = {
                clearing = false
            },
            shape = RoundedCornerShape(
                ZhiBanRadius.Dialog,
            ),
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            title = {
                Text("让知伴忘掉全部？")
            },
            text = { Text("知伴记住的内容会全部删除，无法恢复。你的聊天记录不会被删除。") },
            dismissButton = {
                TextButton(
                    onClick = { clearing = false },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) {
                    Text("取消")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clear()
                    clearing =
                        false
                }) { Text("全部忘掉", color = MaterialTheme.colorScheme.error) }
            },
        )
    }
}

@Composable private fun CompactMemoryToggle(title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, color = ZhiBanTextSecondary, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked, onChecked, modifier = Modifier.scale(.82f))
    }
}

@Composable private fun AddMemoryDialog(busy: Boolean, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var text by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var type by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("PREFERENCE") }
    val types = listOf(
        "PROFILE" to "关于我",
        "PREFERENCE" to "偏好与习惯",
        "RELATIONSHIP" to "家人与朋友",
        "GOAL" to "目标与计划",
        "PROJECT_RULE" to "长期规则",
        "EXPERIENCE" to "知伴经验",
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(
            ZhiBanRadius.Dialog,
        ),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        title = {
            Text("让知伴记住")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Md)) {
                OutlinedTextField(text, {
                    if (it.length <=
                        500
                    ) {
                        text = it
                    }
                }, Modifier.fillMaxWidth(), label = {
                    Text("想让知伴记住什么？")
                }, supportingText = { Text("${text.length}/500") }, minLines = 3)
                Text("这是什么事？", fontWeight = FontWeight.SemiBold)
                types.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Sm)) {
                        row.forEach { entry ->
                            FilterChip(
                                type == entry.first,
                                {
                                    type = entry.first
                                },
                                {
                                    Text(entry.second)
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.primary,
                                ),
                            )
                        }
                    }
                }
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
                onClick = { onSave(text, type) },
                enabled =
                    text.isNotBlank() && !busy,
            ) { Text("记住") }
        },
    )
}

@Composable
private fun EditMemoryDialog(memory: AgentMemoryItem, busy: Boolean, onDismiss: () -> Unit, onSave: (String, String) -> Unit, onDelete: () -> Unit) {
    var text by androidx.compose.runtime.remember(memory.id) { androidx.compose.runtime.mutableStateOf(memory.text) }
    var type by androidx.compose.runtime.remember(memory.id) { androidx.compose.runtime.mutableStateOf(memory.type) }
    val types = listOf(
        "PROFILE" to "关于我",
        "PREFERENCE" to "偏好与习惯",
        "RELATIONSHIP" to "家人与朋友",
        "GOAL" to "目标与计划",
        "PROJECT_RULE" to "长期规则",
        "EXPERIENCE" to "知伴经验",
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(
            ZhiBanRadius.Dialog,
        ),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        title = {
            Text("修改记住的事")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Md)) {
                OutlinedTextField(text, {
                    if (it.length <=
                        500
                    ) {
                        text = it
                    }
                }, Modifier.fillMaxWidth(), label = {
                    Text("知伴记住的内容")
                }, supportingText = { Text("${text.length}/500") }, minLines = 3)
                Text("这是什么事？", fontWeight = FontWeight.SemiBold)
                types.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Sm)) {
                        row.forEach { entry ->
                            FilterChip(
                                type == entry.first,
                                {
                                    type = entry.first
                                },
                                {
                                    Text(entry.second)
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.primary,
                                ),
                            )
                        }
                    }
                }
                TextButton(onClick = onDelete, enabled = !busy) {
                    Text("让知伴忘掉这件事", color = MaterialTheme.colorScheme.error)
                }
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
                onClick = { onSave(text, type) },
                enabled =
                    text.isNotBlank() && !busy,
            ) { Text("保存") }
        },
    )
}

private fun memoryIcon(type: String): ImageVector = when (type) {
    "PROFILE", "FACT" -> Icons.Outlined.Person
    "PREFERENCE" -> Icons.Outlined.FavoriteBorder
    "RELATIONSHIP" -> Icons.Outlined.Groups
    "GOAL" -> Icons.Outlined.Flag
    "PROJECT_RULE" -> Icons.AutoMirrored.Outlined.Rule
    "EXPERIENCE" -> Icons.Outlined.AutoAwesome
    else -> Icons.Outlined.Psychology
}

data class PersonalizationState(val style: ResponseStyle = ResponseStyle.BALANCED, val customInstructions: String = "", val saved: Boolean = false)

@HiltViewModel
class AgentPersonalizationViewModel @Inject constructor(private val store: AgentPersonalizationStore, private val userProfile: UserProfileStore) :
    ViewModel() {
    private val _state = MutableStateFlow(
        store.load().let { PersonalizationState(it.style, userProfile.profile.value.customInstructions) },
    )
    val state = _state.asStateFlow()

    fun style(v: ResponseStyle) = _state.update { it.copy(style = v, saved = false) }
    fun customInstructions(v: String) = _state.update { it.copy(customInstructions = v.take(500), saved = false) }

    fun save() {
        val s = _state.value
        store.save(Personalization(style = s.style))
        if (s.style == ResponseStyle.CUSTOM) {
            userProfile.save(userProfile.profile.value.copy(customInstructions = s.customInstructions))
        }
        _state.update { it.copy(saved = true) }
    }
}

@Composable
fun AgentPersonalizationPage(onBack: () -> Unit, viewModel: AgentPersonalizationViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    ZhiBanPage {
        Column(Modifier.fillMaxSize()) {
            AgentHeader("对话风格", "选择知伴回答问题的方式", onBack)
            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = ZhiBanSpacing.PageHorizontal),
                contentPadding = PaddingValues(bottom = ZhiBanSpacing.PageBottom),
                verticalArrangement = Arrangement.spacedBy(ZhiBanSpacing.ContentGap),
            ) {
                items(ResponseStyle.entries.size) { index ->
                    val style = ResponseStyle.entries[index]
                    ResponseStyleCard(
                        style = style,
                        selected = s.style == style,
                        onClick = { viewModel.style(style) },
                    )
                }
                if (s.style == ResponseStyle.CUSTOM) {
                    item {
                        OutlinedTextField(
                            value = s.customInstructions,
                            onValueChange = viewModel::customInstructions,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("给知伴的指令") },
                            supportingText = { Text("${s.customInstructions.length}/500") },
                            minLines = 3,
                        )
                    }
                }
                item {
                    Button(viewModel::save, Modifier.fillMaxWidth()) {
                        Text(if (s.saved) "已保存" else "保存")
                    }
                }
            }
        }
    }
}

@Composable
private fun ResponseStyleCard(style: ResponseStyle, selected: Boolean, onClick: () -> Unit) {
    ZhiBanGlassCard(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        cornerRadius = ZhiBanRadius.Card,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(
                horizontal = ZhiBanSpacing.Lg,
                vertical = ZhiBanSpacing.Md,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    style.label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = if (selected) ZhiBanTerracotta else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    style.hint,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (selected) {
                Icon(
                    Icons.Outlined.CheckCircle,
                    contentDescription = "已选择",
                    tint = ZhiBanTerracotta,
                    modifier = Modifier.size(ZhiBanIconSize.Inline),
                )
            }
        }
    }
}
data class AgentToolsState(
    val enabled: Map<String, Boolean> = emptyMap(),
    val remoteServers: List<McpRemoteServer> = emptyList(),
    val remoteTools: List<McpRemoteTool> = emptyList(),
    val busy: Boolean = false,
    val message: String? = null,
)
