package com.zhiban.rebuild.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.zhiban.rebuild.data.agent.AgentDataRepository
import com.zhiban.rebuild.data.contact.ContactEntity
import com.zhiban.rebuild.runtime.governance.AutoWriteReceiptRow
import com.zhiban.rebuild.runtime.governance.AutoWriteRepository
import com.zhiban.rebuild.ui.components.ZhiBanPage
import com.zhiban.rebuild.ui.components.ZhiBanTopBar
import com.zhiban.rebuild.ui.components.zhiBanCardSurface
import com.zhiban.rebuild.ui.theme.DateFormats
import com.zhiban.rebuild.ui.theme.ZhiBanSpacing
import com.zhiban.rebuild.ui.theme.ZhiBanTerracotta
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AutoWriteUiState(val receipts: List<AutoWriteReceiptRow> = emptyList(), val contacts: List<ContactEntity> = emptyList())

@HiltViewModel
class AutoWriteViewModel @Inject constructor(private val repository: AutoWriteRepository, agentDataRepository: AgentDataRepository) : ViewModel() {
    val state: StateFlow<AutoWriteUiState> = combine(
        repository.observeReceipts(),
        agentDataRepository.observeContacts(),
    ) { receipts, contacts -> AutoWriteUiState(receipts, contacts) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AutoWriteUiState())

    fun undo(changeId: String, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch { onResult(repository.undo(changeId)) }
    }

    fun correctInteraction(changeId: String, contactId: String, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch { onResult(repository.correctInteractionContact(changeId, contactId)) }
    }

    fun markSeen(changeIds: List<String>) {
        viewModelScope.launch { changeIds.forEach { repository.markSeen(it) } }
    }

    suspend fun consumeFirstHintIfNeeded(hasReceipts: Boolean): Boolean {
        if (!hasReceipts || !repository.shouldShowFirstHint()) return false
        repository.markFirstHintShown()
        return true
    }
}

@Composable
fun AutoWritePage(onBack: () -> Unit, viewModel: AutoWriteViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.receipts.map { it.changeId }) {
        viewModel.markSeen(state.receipts.map { it.changeId })
    }
    AutoWriteContent(
        state = state,
        onBack = onBack,
        onUndo = viewModel::undo,
        onCorrectInteraction = viewModel::correctInteraction,
    )
}

@Composable
internal fun AutoWriteContent(
    state: AutoWriteUiState,
    onBack: () -> Unit,
    onUndo: (String, (Boolean) -> Unit) -> Unit,
    onCorrectInteraction: (String, String, (Boolean) -> Unit) -> Unit,
) {
    var correcting by remember { mutableStateOf<AutoWriteReceiptRow?>(null) }
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
                item { ZhiBanTopBar(title = "自动整理", onBack = onBack) }
                item {
                    Text(
                        "知伴帮你自动整理的内容，可撤销或纠正。保留 90 天。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = ZhiBanSpacing.PageHorizontal),
                    )
                }
                if (state.receipts.isEmpty()) {
                    item {
                        Column(
                            Modifier.fillMaxWidth().padding(
                                horizontal = ZhiBanSpacing.PageHorizontal,
                                vertical = ZhiBanSpacing.Xl,
                            ),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                "还没有自动整理记录",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(ZhiBanSpacing.Sm))
                            Text(
                                "知伴会在你收到消息或打完电话后，自动帮你整理记录。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                } else {
                    val onUndoReceipt: (AutoWriteReceiptRow) -> Unit = { receipt ->
                        onUndo(receipt.changeId) { success ->
                            showSnackbar(if (success) "已撤销" else "内容已被修改，请用纠正处理")
                        }
                    }
                    autoWriteSection(
                        title = "待查看",
                        receipts = state.receipts.filter { it.reviewState == "UNREVIEWED" },
                        onUndo = onUndoReceipt,
                        onCorrect = { correcting = it },
                    )
                    autoWriteSection(
                        title = "已查看",
                        receipts = state.receipts.filter { it.reviewState != "UNREVIEWED" && it.undoState == "AVAILABLE" },
                        onUndo = onUndoReceipt,
                        onCorrect = { correcting = it },
                    )
                    autoWriteSection(
                        title = "已撤销",
                        receipts = state.receipts.filter { it.undoState == "UNDONE" },
                        onUndo = onUndoReceipt,
                        onCorrect = { correcting = it },
                    )
                }
            }
        }
    }

    correcting?.let { receipt ->
        if (receipt.correctionRoute == "CONTACT_PICKER") {
            AlertDialog(
                onDismissRequest = { correcting = null },
                title = { Text("这条互动属于谁？") },
                text = {
                    LazyColumn {
                        items(state.contacts, key = ContactEntity::contactId) { contact ->
                            TextButton(
                                onClick = {
                                    correcting = null
                                    onCorrectInteraction(receipt.changeId, contact.contactId) { success ->
                                        showSnackbar(if (success) "已改为 ${contact.displayName}" else "内容已变化，请重新查看")
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(contact.displayName, modifier = Modifier.fillMaxWidth()) }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = { correcting = null }) { Text("取消") } },
            )
        } else {
            // 非互动摘要类型：无就地纠正入口，提示去对应页面修改。
            LaunchedEffect(receipt.changeId) {
                correcting = null
                showSnackbar("请到联系人或个人 CRM 页面修改这条内容")
            }
        }
    }
}

@Composable
private fun AutoWriteReceiptCard(receipt: AutoWriteReceiptRow, onUndo: () -> Unit, onCorrect: () -> Unit, modifier: Modifier = Modifier) {
    val title = when (receipt.presentationType) {
        "INTERACTION_SUMMARY" -> "整理了一条互动摘要"
        "CONTACT_TAG" -> "补充了联系人标签"
        "CRM_LEAD_CANDIDATE" -> "发现了一条候选线索"
        "CRM_ACTIVITY" -> "记录了一次客户互动"
        "CRM_NEXT_ACTION" -> "创建了下一步动作"
        else -> "完成了一条内部整理"
    }
    Column(
        modifier.fillMaxWidth().zhiBanCardSurface().padding(ZhiBanSpacing.Lg),
        verticalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Sm),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            listOfNotNull(
                receipt.contactName,
                receipt.confidence?.let { "判断 ${(it * 100).toInt()}%" },
                formatAutoWriteTime(receipt.createdAtEpochMs),
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (receipt.undoState == "AVAILABLE") {
                TextButton(onClick = onCorrect) { Text("纠正") }
                TextButton(onClick = onUndo) { Text("撤销", color = ZhiBanTerracotta) }
            } else {
                Text(
                    when (receipt.undoState) {
                        "UNDONE" -> "已撤销"
                        "EXPIRED" -> "撤销期已过"
                        else -> "已处理"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(ZhiBanSpacing.Md),
                )
            }
        }
    }
}

private fun formatAutoWriteTime(epochMs: Long): String = DateFormats.MonthDayTimePadded
    .format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

/** Renders one auto-write section (header + its receipt cards); emits nothing when empty. */
private fun androidx.compose.foundation.lazy.LazyListScope.autoWriteSection(
    title: String,
    receipts: List<AutoWriteReceiptRow>,
    onUndo: (AutoWriteReceiptRow) -> Unit,
    onCorrect: (AutoWriteReceiptRow) -> Unit,
) {
    if (receipts.isEmpty()) return
    item(key = "header-$title") {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = ZhiBanSpacing.PageHorizontal),
        )
    }
    items(receipts, key = { it.changeId }) { receipt ->
        AutoWriteReceiptCard(
            receipt = receipt,
            onUndo = { onUndo(receipt) },
            onCorrect = { onCorrect(receipt) },
            modifier = Modifier.padding(horizontal = ZhiBanSpacing.PageHorizontal),
        )
    }
}
