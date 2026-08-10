package com.zhiban.rebuild.ui.tabs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PeopleOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhiban.rebuild.ui.components.ZhiBanLeadingIcon
import com.zhiban.rebuild.ui.components.ZhiBanPage
import com.zhiban.rebuild.ui.components.ZhiBanSectionTitle
import com.zhiban.rebuild.ui.components.ZhiBanTopBar
import com.zhiban.rebuild.ui.components.zhiBanCardSurface
import com.zhiban.rebuild.ui.theme.ZhiBanIconSize
import com.zhiban.rebuild.ui.theme.ZhiBanSpacing
import com.zhiban.rebuild.ui.theme.ZhiBanTerracottaSoft
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun LifeAssistantPage(
    onBack: () -> Unit,
    onOpenAll: () -> Unit,
    onOpenItem: (String) -> Unit,
    onAskAgent: (String) -> Unit,
    onOpenRelations: () -> Unit,
    viewModel: LifeAssistantViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifeAssistantWorkbench(
        state = state,
        onBack = onBack,
        onOpenAll = onOpenAll,
        onOpenItem = onOpenItem,
        onAskAgent = onAskAgent,
        onOpenRelations = onOpenRelations,
        onConfirm = viewModel::confirmCommitment,
        onDismissMessage = viewModel::clearActionMessage,
    )
}

@Composable
fun LifeAssistantListPage(onBack: () -> Unit, onOpenItem: (String) -> Unit, viewModel: LifeAssistantViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ZhiBanPage {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = ZhiBanSpacing.Xxxl),
            verticalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Md),
        ) {
            item { ZhiBanTopBar("重要的人与事", onBack) }
            if (state.isLoading) {
                item {
                    Box(Modifier.fillMaxWidth().padding(ZhiBanSpacing.Xxxl), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            } else if (state.items.isEmpty()) {
                item {
                    Text(
                        "还没有需要处理的事项",
                        modifier = Modifier.padding(horizontal = ZhiBanSpacing.PageHorizontal),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (state.pendingCommitments.isNotEmpty()) {
                item { LifeSectionTitle("待确认", Modifier.padding(horizontal = ZhiBanSpacing.PageHorizontal)) }
                items(state.pendingCommitments, key = LifeAssistantItem::id) { item ->
                    LifeItemRow(item, { onOpenItem(item.id) }, Modifier.padding(horizontal = ZhiBanSpacing.PageHorizontal))
                }
            }
            if (state.importantDates.isNotEmpty()) {
                item { LifeSectionTitle("重要日期", Modifier.padding(horizontal = ZhiBanSpacing.PageHorizontal)) }
                items(state.importantDates, key = LifeAssistantItem::id) { item ->
                    LifeItemRow(item, { onOpenItem(item.id) }, Modifier.padding(horizontal = ZhiBanSpacing.PageHorizontal))
                }
            }
        }
    }
}

@Composable
fun LifeAssistantDetailPage(itemId: String, onBack: () -> Unit, onAskAgent: (String) -> Unit, viewModel: LifeAssistantViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val item = state.items.firstOrNull { it.id == itemId }
    ZhiBanPage {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = ZhiBanSpacing.Xxxl),
            verticalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Lg),
        ) {
            item { ZhiBanTopBar(if (item?.kind == LifeAssistantItemKind.COMMITMENT) "待确认" else "重要日期", onBack) }
            if (state.isLoading) {
                item { Box(Modifier.fillMaxWidth().padding(ZhiBanSpacing.Xxxl), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            } else if (item == null) {
                item {
                    state.actionMessage?.let { LifeResultMessage(it, viewModel::clearActionMessage) }
                        ?: Text(
                            "这条内容已经处理",
                            modifier = Modifier.padding(horizontal = ZhiBanSpacing.PageHorizontal),
                            style = MaterialTheme.typography.titleMedium,
                        )
                }
            } else {
                item { LifeDetailCard(item, Modifier.padding(horizontal = ZhiBanSpacing.PageHorizontal)) }
                item {
                    Column(
                        modifier = Modifier.padding(horizontal = ZhiBanSpacing.PageHorizontal),
                        verticalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Md),
                    ) {
                        if (item.kind == LifeAssistantItemKind.COMMITMENT) {
                            Button(
                                onClick = { viewModel.confirmCommitment(item) },
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                            ) { Text("加入日历") }
                            OutlinedButton(
                                onClick = { viewModel.dismissCommitment(item) },
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                            ) { Text("忽略") }
                        } else {
                            Button(
                                onClick = { onAskAgent(lifePlanningPrompt(item)) },
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                            ) { Text("开始安排") }
                        }
                        TextButton(
                            onClick = { onAskAgent(lifeQuestionPrompt(item)) },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                        ) { Text("问问知伴") }
                    }
                }
                state.actionMessage?.let { message ->
                    item { LifeResultMessage(message, viewModel::clearActionMessage) }
                }
            }
        }
    }
}

@Composable
internal fun LifeAssistantWorkbench(
    state: LifeAssistantState,
    onBack: () -> Unit,
    onOpenAll: () -> Unit,
    onOpenItem: (String) -> Unit,
    onAskAgent: (String) -> Unit,
    onOpenRelations: () -> Unit,
    onConfirm: (LifeAssistantItem) -> Unit,
    onDismissMessage: () -> Unit,
) {
    val spotlight = state.spotlight
    val now = System.currentTimeMillis()
    val nextSevenDays = state.importantDates
        .filter { it.id != spotlight?.id && it.eventAtEpochMs <= now + SEVEN_DAYS_MS }
        .take(3)
    ZhiBanPage {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = ZhiBanSpacing.Xxxl),
            verticalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Section),
        ) {
            item { ZhiBanTopBar("生活助理", onBack, subtitle = "重要的人与事") }
            state.actionMessage?.let { message -> item { LifeResultMessage(message, onDismissMessage) } }
            when {
                state.isLoading -> item {
                    Box(Modifier.fillMaxWidth().padding(ZhiBanSpacing.Xxxl), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }

                spotlight == null -> item {
                    LifeEmptyWorkbench(onOpenRelations, onAskAgent, Modifier.padding(horizontal = ZhiBanSpacing.PageHorizontal))
                }

                else -> {
                    item { LifeSectionTitle("现在", Modifier.padding(horizontal = ZhiBanSpacing.PageHorizontal)) }
                    item {
                        LifeSpotlightCard(
                            item = spotlight,
                            onOpen = { onOpenItem(spotlight.id) },
                            onPrimary = if (spotlight.kind == LifeAssistantItemKind.COMMITMENT) {
                                { onConfirm(spotlight) }
                            } else {
                                { onAskAgent(lifePlanningPrompt(spotlight)) }
                            },
                            modifier = Modifier.padding(horizontal = ZhiBanSpacing.PageHorizontal),
                        )
                    }
                    if (nextSevenDays.isNotEmpty()) {
                        item { LifeSectionTitle("接下来 7 天", Modifier.padding(horizontal = ZhiBanSpacing.PageHorizontal)) }
                        items(nextSevenDays, key = { "week-${it.id}" }) { item ->
                            LifeItemRow(item, { onOpenItem(item.id) }, Modifier.padding(horizontal = ZhiBanSpacing.PageHorizontal))
                        }
                    }
                    if (state.items.size > 1) {
                        item {
                            ZhiBanSectionTitle(
                                title = "全部",
                                action = "查看 ${state.items.size} 项",
                                onActionClick = onOpenAll,
                                modifier = Modifier.padding(horizontal = ZhiBanSpacing.PageHorizontal),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LifeSpotlightCard(item: LifeAssistantItem, onOpen: () -> Unit, onPrimary: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().zhiBanCardSurface(ZhiBanTerracottaSoft).clickable(onClick = onOpen).padding(ZhiBanSpacing.Xl),
        verticalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ZhiBanLeadingIcon(lifeIcon(item), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.size(ZhiBanSpacing.Md))
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(lifeDateLabel(item), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item.contactName?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        Button(onClick = onPrimary, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Text(if (item.kind == LifeAssistantItemKind.COMMITMENT) "加入日历" else "开始安排")
        }
    }
}

@Composable
private fun LifeItemRow(item: LifeAssistantItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = ZhiBanSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZhiBanLeadingIcon(lifeIcon(item))
        Spacer(Modifier.size(ZhiBanSpacing.Md))
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(lifeDateLabel(item), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, modifier = Modifier.size(ZhiBanIconSize.Inline))
    }
}

@Composable
private fun LifeDetailCard(item: LifeAssistantItem, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().zhiBanCardSurface().padding(ZhiBanSpacing.Xl),
        verticalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Md),
    ) {
        ZhiBanLeadingIcon(lifeIcon(item))
        Text(item.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(lifeDateLabel(item), style = MaterialTheme.typography.bodyLarge)
        item.contactName?.let { LifeDetailLine("联系人", it) }
        LifeDetailLine("来源", item.sourceLabel)
        item.evidence?.let { LifeDetailLine("原消息", it) }
        if (item.confidence < 1.0) LifeDetailLine("判断", "${(item.confidence * 100).toInt()}% 可信")
    }
}

@Composable
private fun LifeDetailLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Xs)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun LifeEmptyWorkbench(onOpenRelations: () -> Unit, onAskAgent: (String) -> Unit, modifier: Modifier = Modifier) {
    SceneCapabilityEmptyState(
        icon = Icons.Outlined.PeopleOutline,
        title = "从重要的人开始",
        supportingText = "完善重要日期后，知伴会整理生日和约定",
        primaryLabel = "查看联系人",
        onPrimary = onOpenRelations,
        modifier = modifier,
        secondaryLabel = "问问知伴",
        onSecondary = { onAskAgent("帮我整理最近需要关心的人和已经答应的事情") },
        testTag = "life-empty-workbench",
    )
}

@Composable
private fun LifeResultMessage(message: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier.padding(horizontal = ZhiBanSpacing.PageHorizontal).fillMaxWidth()
            .zhiBanCardSurface(MaterialTheme.colorScheme.primaryContainer).clickable(onClick = onDismiss).padding(ZhiBanSpacing.Lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Info, null, modifier = Modifier.size(ZhiBanIconSize.Inline))
        Spacer(Modifier.size(ZhiBanSpacing.Sm))
        Text(message, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun LifeSectionTitle(title: String, modifier: Modifier = Modifier) {
    Text(title, modifier = modifier, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

private fun lifeIcon(item: LifeAssistantItem) = when (item.kind) {
    LifeAssistantItemKind.COMMITMENT -> Icons.Outlined.CalendarMonth
    LifeAssistantItemKind.IMPORTANT_DATE -> Icons.Outlined.FavoriteBorder
}

internal fun lifePlanningPrompt(item: LifeAssistantItem): String = "帮我为${item.title}做准备。先查看相关联系人和已有日程，给出一个简洁安排；需要写入日历时先让我确认。"

internal fun lifeQuestionPrompt(item: LifeAssistantItem): String = "帮我看看${item.title}该怎么处理。请结合联系人资料和日程，只使用可核实的信息。"

internal fun lifeDateLabel(item: LifeAssistantItem, zoneId: ZoneId = ZoneId.systemDefault()): String {
    val dateTime = Instant.ofEpochMilli(item.eventAtEpochMs).atZone(zoneId)
    val date = dateTime.toLocalDate()
    val today = LocalDate.now(zoneId)
    val dayLabel = when (date) {
        today -> "今天"
        today.plusDays(1) -> "明天"
        else -> date.format(MONTH_DAY_FORMAT)
    }
    return if (item.kind == LifeAssistantItemKind.COMMITMENT) "$dayLabel · ${dateTime.format(TIME_FORMAT)}" else dayLabel
}

private val MONTH_DAY_FORMAT = DateTimeFormatter.ofPattern("M月d日")
private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")
private const val SEVEN_DAYS_MS = 7 * 24 * 60 * 60 * 1_000L
