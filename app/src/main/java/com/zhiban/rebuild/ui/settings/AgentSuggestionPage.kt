package com.zhiban.rebuild.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.zhiban.rebuild.data.completion.ContactCompletionDraft
import com.zhiban.rebuild.data.suggestion.AgentSuggestionCodecs
import com.zhiban.rebuild.data.suggestion.AgentSuggestionEntity
import com.zhiban.rebuild.data.suggestion.AgentSuggestionRepository
import com.zhiban.rebuild.data.suggestion.AgentSuggestionStatus
import com.zhiban.rebuild.data.suggestion.AgentSuggestionType
import com.zhiban.rebuild.data.suggestion.EventIntentExtractor.ContactCandidate
import com.zhiban.rebuild.ui.components.ZhiBanAlertDialog
import com.zhiban.rebuild.ui.components.ZhiBanPage
import com.zhiban.rebuild.ui.components.ZhiBanTopBar
import com.zhiban.rebuild.ui.components.zhiBanCardSurface
import com.zhiban.rebuild.ui.tabs.ContactCompletionCard
import com.zhiban.rebuild.ui.theme.DateFormats
import com.zhiban.rebuild.ui.theme.ZhiBanSpacing
import com.zhiban.rebuild.ui.theme.ZhiBanTerracotta
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AgentSuggestionUiState(val suggestions: List<AgentSuggestionEntity> = emptyList(), val hasMore: Boolean = false)

/**
 * 智能建议中心 ViewModel：事件唤醒 LLM 判断后的产出在此统一到达用户。
 * "接受"表示认可该判断（已由 agent 执行的动作见自动整理收据，此处仅做意图确认），"忽略"静默关闭。
 */
@HiltViewModel
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AgentSuggestionViewModel @Inject constructor(private val repository: AgentSuggestionRepository) : ViewModel() {
    private val loadedLimit = MutableStateFlow(PAGE_SIZE)
    val state: StateFlow<AgentSuggestionUiState> = loadedLimit
        .flatMapLatest { limit ->
            repository.observeSuggestions(limit + 1).map { rows ->
                AgentSuggestionUiState(rows.take(limit), hasMore = rows.size > limit)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AgentSuggestionUiState())

    val pendingCount: StateFlow<Int> = repository.observePendingCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun accept(suggestionId: String, chosenContactId: String? = null, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch { onResult(repository.accept(suggestionId, chosenContactId)) }
    }

    /** 补全建议卡的一键转发：加载对话框所需草稿视图（请求 id + 联系人 + 字段 + 文案）。 */
    fun completionDraft(suggestionId: String, onResult: (ContactCompletionDraft?) -> Unit) {
        viewModelScope.launch { onResult(repository.completionDraft(suggestionId)) }
    }

    /** 用户编辑后确认 → 拉起微信预填（成功才 ACCEPTED）。失败保持 PENDING 可重试。 */
    fun completeAndHandoff(suggestionId: String, finalText: String, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch { onResult(repository.completeAndHandoff(suggestionId, finalText)) }
    }

    fun dismiss(suggestionId: String, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch { onResult(repository.dismiss(suggestionId)) }
    }

    fun loadMore() {
        loadedLimit.value += PAGE_SIZE
    }

    private companion object {
        const val PAGE_SIZE = 50
    }
}

@Composable
fun AgentSuggestionPage(onBack: () -> Unit, viewModel: AgentSuggestionViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AgentSuggestionContent(
        state = state,
        onBack = onBack,
        onAccept = viewModel::accept,
        onDismiss = viewModel::dismiss,
        onLoadCompletionDraft = viewModel::completionDraft,
        onCompleteAndHandoff = viewModel::completeAndHandoff,
        onLoadMore = viewModel::loadMore,
    )
}

@Composable
internal fun AgentSuggestionContent(
    state: AgentSuggestionUiState,
    onBack: () -> Unit,
    onAccept: (String, String?, (Boolean) -> Unit) -> Unit,
    onDismiss: (String, (Boolean) -> Unit) -> Unit,
    onLoadCompletionDraft: (String, (ContactCompletionDraft?) -> Unit) -> Unit,
    onCompleteAndHandoff: (String, String, (Boolean) -> Unit) -> Unit,
    onLoadMore: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val showSnackbar: (String) -> Unit = { message -> scope.launch { snackbarHostState.showSnackbar(message) } }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { innerPadding ->
        ZhiBanPage {
            LazyColumn(
                Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(bottom = ZhiBanSpacing.Xxxl),
                verticalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Md),
            ) {
                item { ZhiBanTopBar(title = "智能建议", onBack = onBack) }
                if (state.suggestions.isEmpty()) {
                    item {
                        Column(
                            Modifier.fillMaxWidth().padding(
                                horizontal = ZhiBanSpacing.PageHorizontal,
                                vertical = ZhiBanSpacing.Xl,
                            ),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                "暂无智能建议",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center,
                            )
                            Text(
                                "知伴在感知到值得注意的消息或事件时会主动给出判断",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                } else {
                    val acceptAction: (String, String?) -> Unit = { suggestionId, chosen ->
                        onAccept(suggestionId, chosen) { success ->
                            showSnackbar(if (success) "已完成" else "未能完成，请检查后重试")
                        }
                    }
                    val dismissAction: (String) -> Unit = { suggestionId -> onDismiss(suggestionId) {} }
                    agentSuggestionSection(
                        title = "待处理",
                        suggestions = state.suggestions.filter { it.status == AgentSuggestionStatus.PENDING },
                        onAccept = acceptAction,
                        onDismiss = dismissAction,
                        onLoadCompletionDraft = onLoadCompletionDraft,
                        onCompleteAndHandoff = onCompleteAndHandoff,
                    )
                    agentSuggestionSection(
                        title = "已处理",
                        suggestions = state.suggestions.filter { it.status != AgentSuggestionStatus.PENDING },
                        onAccept = acceptAction,
                        onDismiss = dismissAction,
                        onLoadCompletionDraft = onLoadCompletionDraft,
                        onCompleteAndHandoff = onCompleteAndHandoff,
                    )
                    if (state.hasMore) {
                        item(key = "load-more") {
                            TextButton(
                                onClick = onLoadMore,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = ZhiBanSpacing.PageHorizontal),
                            ) {
                                Text("查看更多")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentSuggestionCard(
    suggestion: AgentSuggestionEntity,
    onAccept: (String?) -> Unit,
    onDismiss: () -> Unit,
    onLoadCompletionDraft: (String, (ContactCompletionDraft?) -> Unit) -> Unit,
    onCompleteAndHandoff: (String, String, (Boolean) -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showConfirm by rememberSaveable(suggestion.suggestionId) { mutableStateOf(false) }
    var completionDraft by remember { mutableStateOf<ContactCompletionDraft?>(null) }
    Column(
        modifier.fillMaxWidth().zhiBanCardSurface().padding(ZhiBanSpacing.Lg),
        verticalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Sm),
    ) {
        Text(suggestion.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            listOfNotNull(
                suggestionTypeLabel(suggestion.type),
                formatSuggestionTime(suggestion.createdAtEpochMs),
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // 结构化日程要素（时间/双地点/客户/行程/候选/待确认项）
        if (suggestion.execActionType == EXEC_SCHEDULE && suggestion.startAtEpochMs != null) {
            val scheduleLines = listOfNotNull(
                suggestion.startAtEpochMs?.let { "时间：${formatSuggestionTime(it)}" },
                suggestion.pickupLocation?.let { "接人：$it" },
                suggestion.visitLocation?.let { "拜访：$it${visitLocationSourceLabel(suggestion.visitLocationSource)}" },
                if (suggestion.visitLocation == null) suggestion.location?.let { "地点：$it" } else null,
                suggestion.companyFull?.let { "客户：$it" },
                suggestion.departAtEpochMs?.let { "建议 ${formatClock(it)} 出发" },
            )
            scheduleLines.forEach { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            suggestion.travelNote?.takeIf(String::isNotBlank)?.let { note ->
                Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AgentSuggestionCodecs.decodeCandidates(suggestion.contactCandidatesJson)
                .takeIf(List<ContactCandidate>::isNotEmpty)
                ?.let { candidates ->
                    Text(
                        "对接人候选：${candidates.joinToString("、") { it.name + (it.title?.let { t -> "（$t）" } ?: "") }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            suggestion.confirmNotes?.takeIf(String::isNotBlank)?.let { notes ->
                Text(
                    "待确认：${notes.replace("\n", "；")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = ZhiBanTerracotta,
                )
            }
        }
        // 一键转发补全：缺失字段 chips + 起草好的消息预览
        if (suggestion.execActionType == EXEC_COMPLETION) {
            val missingFields = AgentSuggestionCodecs.decodeMissingFields(suggestion.missingFieldsJson)
            if (missingFields.isNotEmpty()) {
                Text(
                    "资料待补全：${missingFields.joinToString("、") { it.label }}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = ZhiBanTerracotta,
                )
            }
            suggestion.forwardMessage?.takeIf(String::isNotBlank)?.let { draft ->
                Text(
                    "草稿：$draft",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        suggestion.body.takeIf(String::isNotBlank)?.let { body ->
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (suggestion.status == AgentSuggestionStatus.PENDING) {
                TextButton(onClick = onDismiss) { Text("忽略", color = ZhiBanTerracotta) }
                TextButton(
                    onClick = {
                        if (suggestion.execActionType == EXEC_COMPLETION) {
                            // 加载补全草稿对话框；失败（请求已失效等）直接按普通接受处理。
                            onLoadCompletionDraft(suggestion.suggestionId) { draft ->
                                if (draft != null) completionDraft = draft else onAccept(null)
                            }
                        } else {
                            val hasConfirm = !suggestion.confirmNotes.isNullOrBlank() ||
                                AgentSuggestionCodecs.decodeCandidates(suggestion.contactCandidatesJson).isNotEmpty()
                            if (hasConfirm) showConfirm = true else onAccept(null)
                        }
                    },
                ) {
                    Text(
                        when (suggestion.execActionType) {
                            EXEC_SCHEDULE -> "接受并创建日程"
                            EXEC_COMPLETION -> "一键转发补全"
                            else -> "接受"
                        },
                    )
                }
            } else {
                Text(
                    when {
                        suggestion.planId != null -> "已创建日程"
                        suggestion.execActionType == EXEC_COMPLETION && suggestion.status == AgentSuggestionStatus.ACCEPTED -> "已转发"
                        suggestion.status == AgentSuggestionStatus.ACCEPTED -> "已采纳"
                        else -> "已忽略"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(ZhiBanSpacing.Md),
                )
            }
        }
    }
    if (showConfirm) {
        ScheduleConfirmDialog(
            suggestion = suggestion,
            onConfirm = { chosen ->
                showConfirm = false
                onAccept(chosen)
            },
            onDismiss = { showConfirm = false },
        )
    }
    completionDraft?.let { draft ->
        ContactCompletionCard(
            draft = draft,
            error = null,
            onConfirm = { finalText ->
                onCompleteAndHandoff(suggestion.suggestionId, finalText) { _ -> completionDraft = null }
            },
            onCancel = { completionDraft = null },
        )
    }
}

/**
 * 接受并创建日程前的确认面板：展示待确认项，若有对接人候选可单选其一；
 * 无待确认项时直接确认（等价于旧行为）。
 */
@Composable
private fun ScheduleConfirmDialog(suggestion: AgentSuggestionEntity, onConfirm: (String?) -> Unit, onDismiss: () -> Unit) {
    val candidates = AgentSuggestionCodecs.decodeCandidates(suggestion.contactCandidatesJson)
    val confirmNotes = suggestion.confirmNotes?.takeIf(String::isNotBlank)
    val needsChoice = candidates.isNotEmpty() || !confirmNotes.isNullOrBlank()
    // 仅持久化选中项 id（String 可存 Bundle）；配置变更/进程重建后仍能还原选中态。
    var selectedContactId by rememberSaveable(suggestion.suggestionId) { mutableStateOf<String?>(null) }
    val selected = candidates.firstOrNull { it.contactId == selectedContactId }
    val requiresCandidateSelection = suggestion.contactId == null && candidates.isNotEmpty()

    ZhiBanAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("确认日程信息") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Sm)) {
                confirmNotes?.split("\n")?.filter(String::isNotBlank)?.forEach { note ->
                    Text("· $note", style = MaterialTheme.typography.bodySmall, color = ZhiBanTerracotta)
                }
                if (candidates.isNotEmpty()) {
                    Text("拜访对象（可任选其一）：", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                    candidates.forEach { candidate ->
                        FilterChip(
                            selected = selected?.contactId == candidate.contactId,
                            onClick = { selectedContactId = candidate.contactId },
                            label = {
                                Text(
                                    candidate.name + (candidate.title?.let { " · $it" } ?: ""),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            modifier = Modifier.fillMaxWidth().widthIn(max = 260.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(selected?.contactId) },
                enabled = !requiresCandidateSelection || selected != null,
            ) {
                Text(if (needsChoice) "确认并创建日程" else "创建日程")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

private fun visitLocationSourceLabel(source: String?): String = when (source) {
    "CONTACT" -> "（联系人库）"
    "REGISTRY" -> "（注册地址）"
    else -> ""
}

private fun formatClock(epochMs: Long): String = DateFormats.Time
    .format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

private const val EXEC_SCHEDULE = "SCHEDULE"
private const val EXEC_COMPLETION = "CONTACT_COMPLETION"

private fun suggestionTypeLabel(type: String): String = when (type) {
    AgentSuggestionType.WAKEUP_CONTACT -> "联系人"
    AgentSuggestionType.WAKEUP_CRM -> "CRM"
    AgentSuggestionType.WAKEUP_SCHEDULE -> "日程"
    AgentSuggestionType.WAKEUP_IDENTITY -> "身份"
    else -> "综合判断"
}

private fun formatSuggestionTime(epochMs: Long): String = DateFormats.MonthDayTimePadded
    .format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

/** Renders one suggestion section (header + cards); emits nothing when empty. */
private fun LazyListScope.agentSuggestionSection(
    title: String,
    suggestions: List<AgentSuggestionEntity>,
    onAccept: (String, String?) -> Unit,
    onDismiss: (String) -> Unit,
    onLoadCompletionDraft: (String, (ContactCompletionDraft?) -> Unit) -> Unit,
    onCompleteAndHandoff: (String, String, (Boolean) -> Unit) -> Unit,
) {
    if (suggestions.isEmpty()) return
    item(key = "header-$title") {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = ZhiBanSpacing.PageHorizontal),
        )
    }
    items(suggestions, key = { it.suggestionId }) { suggestion ->
        AgentSuggestionCard(
            suggestion = suggestion,
            onAccept = { chosen -> onAccept(suggestion.suggestionId, chosen) },
            onDismiss = { onDismiss(suggestion.suggestionId) },
            onLoadCompletionDraft = onLoadCompletionDraft,
            onCompleteAndHandoff = onCompleteAndHandoff,
            modifier = Modifier.padding(horizontal = ZhiBanSpacing.PageHorizontal),
        )
    }
}
