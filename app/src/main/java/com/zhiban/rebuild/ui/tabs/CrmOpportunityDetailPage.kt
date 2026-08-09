package com.zhiban.rebuild.ui.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhiban.rebuild.data.crm.CrmActionStatus
import com.zhiban.rebuild.data.crm.CrmActivityEntity
import com.zhiban.rebuild.data.crm.CrmOpportunityStage
import com.zhiban.rebuild.data.crm.CrmStageHistoryEntity
import com.zhiban.rebuild.ui.components.ZhiBanPage
import com.zhiban.rebuild.ui.components.ZhiBanTopBar
import com.zhiban.rebuild.ui.components.zhiBanCardSurface
import com.zhiban.rebuild.ui.theme.ZhiBanIconSize
import com.zhiban.rebuild.ui.theme.ZhiBanRadius
import com.zhiban.rebuild.ui.theme.ZhiBanSize
import com.zhiban.rebuild.ui.theme.ZhiBanSpacing
import com.zhiban.rebuild.ui.theme.ZhiBanTerracotta
import com.zhiban.rebuild.ui.theme.ZhiBanTerracottaSoft

@Composable
fun CrmOpportunityDetailPage(
    opportunityId: String,
    onBack: () -> Unit,
    onOpenCalendar: (Long?) -> Unit,
    onOpenRelation: () -> Unit,
    onAskAgent: (String) -> Unit,
    viewModel: CrmCapabilityViewModel = hiltViewModel(),
) {
    val state by viewModel.detailState.collectAsStateWithLifecycle()
    val opportunity = state.opportunity
    var showStageDialog by remember { mutableStateOf(false) }

    LaunchedEffect(opportunityId) { viewModel.selectOpportunity(opportunityId) }

    ZhiBanPage {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = ZhiBanSpacing.Xxxl),
            verticalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Section),
        ) {
            item {
                ZhiBanTopBar(
                    title = opportunity?.entity?.title ?: "机会详情",
                    subtitle = opportunity?.entity?.accountNameSnapshot ?: if (state.isLoading) "正在读取" else "个人 CRM",
                    onBack = onBack,
                    trailing = opportunity?.entity?.sourceType?.let { sourceType ->
                        if (sourceType == "DEMO") {
                            (
                                {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        CrmDemoBadge()
                                        TextButton(onClick = viewModel::exitDemo) { Text("退出演示") }
                                    }
                                }
                                )
                        } else {
                            null
                        }
                    },
                )
            }

            when {
                state.isLoading -> item {
                    CrmDetailPageState(
                        title = "正在读取机会",
                        message = "稍等一下，知伴正在整理联系人和推进记录。",
                        loading = true,
                    )
                }

                state.errorMessage != null -> item {
                    CrmDetailPageState(
                        title = "暂时无法读取",
                        message = state.errorMessage.orEmpty(),
                    )
                }

                opportunity == null -> item {
                    CrmDetailPageState(
                        title = "没有找到这条机会",
                        message = "它可能已被删除或退出了演示模式。",
                    )
                }

                else -> {
                    item {
                        Column(
                            Modifier.padding(horizontal = ZhiBanSpacing.PageHorizontal),
                            verticalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Lg),
                        ) {
                            CrmSectionHeader(
                                title = "推进阶段",
                                action = if (opportunity.entity.stage in CrmOpportunityStage.terminalStages) null else "调整",
                                onAction = if (opportunity.entity.stage in
                                    CrmOpportunityStage.terminalStages
                                ) {
                                    null
                                } else {
                                    ({ showStageDialog = true })
                                },
                            )
                            CrmStageProgress(opportunity.entity.stage)
                        }
                    }

                    item {
                        CrmDealFacts(opportunity, Modifier.padding(horizontal = ZhiBanSpacing.PageHorizontal))
                    }

                    item {
                        CrmSectionHeader(
                            title = "下一步动作",
                            modifier = Modifier.padding(horizontal = ZhiBanSpacing.PageHorizontal),
                        )
                    }
                    val pendingActions = state.actions.filter { it.entity.status == CrmActionStatus.PENDING }
                    if (pendingActions.isEmpty()) {
                        item {
                            Text(
                                "还没有已确认的下一步动作。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = ZhiBanSpacing.PageHorizontal),
                            )
                        }
                    } else {
                        items(pendingActions, key = { it.entity.actionId }) { action ->
                            CrmDetailAction(
                                action = action,
                                onCalendar = { onOpenCalendar(action.entity.dueAtEpochMs) },
                                onComplete = { viewModel.completeAction(action.entity.actionId) },
                                modifier = Modifier.padding(horizontal = ZhiBanSpacing.PageHorizontal),
                            )
                        }
                    }

                    if (state.suggestions.any { it.entity.status == "PENDING" }) {
                        item {
                            CrmSectionHeader(
                                title = "知伴建议",
                                modifier = Modifier.padding(horizontal = ZhiBanSpacing.PageHorizontal),
                            )
                        }
                        items(
                            state.suggestions.filter {
                                it.entity.status == "PENDING"
                            },
                            key = { it.entity.suggestionId },
                        ) { suggestion ->
                            CrmDetailSuggestion(
                                suggestion = suggestion,
                                onAskAgent = {
                                    onAskAgent(
                                        crmSuggestionPrompt(
                                            opportunityId = opportunity.entity.opportunityId,
                                            suggestionTitle = suggestion.entity.title,
                                            isDemo = opportunity.entity.sourceType == "DEMO",
                                        ),
                                    )
                                },
                                onDismiss = { viewModel.dismissSuggestion(suggestion.entity.suggestionId) },
                                modifier = Modifier.padding(horizontal = ZhiBanSpacing.PageHorizontal),
                            )
                        }
                    }

                    item {
                        CrmSectionHeader(
                            title = "关键关系人",
                            action = "关系",
                            onAction = onOpenRelation,
                            modifier = Modifier.padding(horizontal = ZhiBanSpacing.PageHorizontal),
                        )
                    }
                    if (state.stakeholders.isEmpty()) {
                        item {
                            Text(
                                "还没有确认决策人、推动者或使用人。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = ZhiBanSpacing.PageHorizontal),
                            )
                        }
                    } else {
                        item {
                            Column(
                                Modifier.padding(
                                    horizontal = ZhiBanSpacing.PageHorizontal,
                                ).fillMaxWidth().zhiBanCardSurface(),
                            ) {
                                state.stakeholders.forEachIndexed { index, stakeholder ->
                                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                    Row(
                                        Modifier.fillMaxWidth().defaultMinSize(
                                            minHeight = ZhiBanSize.ListRowWithSubtitle,
                                        ).clickable(onClick = onOpenRelation)
                                            .padding(horizontal = ZhiBanSpacing.Lg, vertical = ZhiBanSpacing.Md),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Box(
                                            Modifier.size(40.dp).clip(CircleShape).background(ZhiBanTerracottaSoft),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(
                                                stakeholder.contactName.take(1),
                                                color = ZhiBanTerracotta,
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                        }
                                        Spacer(Modifier.width(ZhiBanSpacing.Md))
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                stakeholder.contactName,
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                            Text(
                                                listOfNotNull(stakeholder.company, stakeholder.title).joinToString(" · "),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        Text(
                                            crmStakeholderRoleLabel(stakeholder.entity.roleType),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = ZhiBanTerracotta,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    item {
                        CrmSectionHeader(
                            title = "推进记录",
                            modifier = Modifier.padding(horizontal = ZhiBanSpacing.PageHorizontal),
                        )
                    }
                    items(state.activities, key = CrmActivityEntity::activityId) { activity ->
                        CrmActivityRow(activity, Modifier.padding(horizontal = ZhiBanSpacing.PageHorizontal))
                    }

                    if (state.stageHistory.isNotEmpty()) {
                        item {
                            CrmSectionHeader(
                                title = "阶段记录",
                                modifier = Modifier.padding(horizontal = ZhiBanSpacing.PageHorizontal),
                            )
                        }
                        items(state.stageHistory.take(4), key = CrmStageHistoryEntity::historyId) { history ->
                            CrmHistoryRow(history, Modifier.padding(horizontal = ZhiBanSpacing.PageHorizontal))
                        }
                    }
                }
            }
        }
    }

    if (showStageDialog && opportunity != null) {
        CrmStageDialog(
            currentStage = opportunity.entity.stage,
            onDismiss = { showStageDialog = false },
            onConfirm = { stage ->
                showStageDialog = false
                viewModel.changeStage(opportunity.entity.opportunityId, stage)
            },
        )
    }
}

@Composable
internal fun CrmDetailPageState(title: String, message: String, loading: Boolean = false) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = ZhiBanSpacing.PageHorizontal)
            .zhiBanCardSurface().padding(ZhiBanSpacing.Xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Md),
    ) {
        if (loading) CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CrmDealFacts(opportunity: CrmOpportunityUi, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().zhiBanCardSurface().padding(ZhiBanSpacing.Lg),
        verticalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Lg),
    ) {
        Row(Modifier.fillMaxWidth()) {
            CrmFact(
                "预计金额",
                formatCrmMoney(opportunity.entity.valueMinor, opportunity.entity.currencyCode),
                Modifier.weight(1f),
            )
            CrmFact("预计成交", formatCrmDate(opportunity.entity.expectedCloseAtEpochMs), Modifier.weight(1f))
            CrmFact("当前概率", "${opportunity.entity.probabilityPercent}%", Modifier.weight(1f))
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(verticalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Xs)) {
            Text(
                "对方需求",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                opportunity.entity.needSummary ?: "需求待确认",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Xs)) {
            Text(
                "产品或方案",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                opportunity.entity.productSummary ?: "方案待确认",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun CrmFact(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Xs)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CrmDetailAction(action: CrmActionUi, onCalendar: () -> Unit, onComplete: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().zhiBanCardSurface().padding(ZhiBanSpacing.Lg),
        verticalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.Schedule,
                contentDescription = null,
                tint = ZhiBanTerracotta,
                modifier = Modifier.size(ZhiBanIconSize.Leading),
            )
            Spacer(Modifier.width(ZhiBanSpacing.Md))
            Column(Modifier.weight(1f)) {
                Text(action.entity.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    formatCrmDateTime(action.entity.dueAtEpochMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = ZhiBanTerracotta,
                )
            }
        }
        action.entity.rationale?.let {
            Text(
                "依据：$it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Md)) {
            OutlinedButton(onClick = onCalendar, Modifier.weight(1f).height(ZhiBanSize.Control)) {
                Icon(
                    Icons.Outlined.CalendarMonth,
                    contentDescription = null,
                    modifier = Modifier.size(ZhiBanIconSize.Inline),
                )
                Spacer(Modifier.width(ZhiBanSpacing.Sm))
                Text("查看日历")
            }
            Button(
                onClick = onComplete,
                Modifier.weight(1f).height(ZhiBanSize.Control),
                colors = ButtonDefaults.buttonColors(containerColor = ZhiBanTerracotta),
            ) {
                Icon(
                    Icons.Outlined.CheckCircleOutline,
                    contentDescription = null,
                    modifier = Modifier.size(ZhiBanIconSize.Inline),
                )
                Spacer(Modifier.width(ZhiBanSpacing.Sm))
                Text("标记完成")
            }
        }
    }
}

@Composable
private fun CrmDetailSuggestion(suggestion: CrmSuggestionUi, onAskAgent: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().zhiBanCardSurface(ZhiBanTerracottaSoft).padding(ZhiBanSpacing.Lg),
        verticalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.AutoAwesome,
                contentDescription = null,
                tint = ZhiBanTerracotta,
                modifier = Modifier.size(ZhiBanIconSize.Leading),
            )
            Spacer(Modifier.width(ZhiBanSpacing.Md))
            Text(suggestion.entity.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
        Text(suggestion.entity.summary, style = MaterialTheme.typography.bodyMedium)
        Text(
            "判断依据：${suggestion.entity.rationale}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) { Text("忽略", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            TextButton(onClick = onAskAgent) { Text("让知伴准备", color = ZhiBanTerracotta) }
        }
    }
}

@Composable
private fun CrmActivityRow(activity: CrmActivityEntity, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.History,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(ZhiBanIconSize.Inline),
                )
            }
            Box(Modifier.width(1.dp).height(44.dp).background(MaterialTheme.colorScheme.outlineVariant))
        }
        Spacer(Modifier.width(ZhiBanSpacing.Md))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Xs)) {
            Text(activity.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                activity.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                formatCrmDateTime(activity.occurredAtEpochMs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CrmHistoryRow(history: CrmStageHistoryEntity, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().defaultMinSize(
            minHeight = ZhiBanSize.ListRow,
        ).zhiBanCardSurface().padding(ZhiBanSpacing.Lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Groups,
            contentDescription = null,
            tint = ZhiBanTerracotta,
            modifier = Modifier.size(ZhiBanIconSize.Leading),
        )
        Spacer(Modifier.width(ZhiBanSpacing.Md))
        Column(Modifier.weight(1f)) {
            Text(
                "${history.fromStage?.let(::crmStageLabel) ?: "建立机会"} → ${crmStageLabel(history.toStage)}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                history.reason ?: "阶段已更新",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            formatCrmDate(history.changedAtEpochMs),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CrmStageDialog(currentStage: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var pendingStage by remember(currentStage) { mutableStateOf(currentStage) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(ZhiBanRadius.Dialog),
        title = { Text("调整推进阶段") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Xs)) {
                (crmVisibleStages + CrmOpportunityStage.LOST).forEach { stage ->
                    Row(
                        Modifier.fillMaxWidth().defaultMinSize(
                            minHeight = ZhiBanSize.TouchTarget,
                        ).clip(androidx.compose.foundation.shape.RoundedCornerShape(ZhiBanRadius.Small))
                            .background(
                                if (pendingStage ==
                                    stage
                                ) {
                                    ZhiBanTerracottaSoft
                                } else {
                                    MaterialTheme.colorScheme.surface
                                },
                            )
                            .clickable { pendingStage = stage }.padding(horizontal = ZhiBanSpacing.Md),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            crmStageLabel(stage),
                            Modifier.weight(1f),
                            color = if (pendingStage ==
                                stage
                            ) {
                                ZhiBanTerracotta
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                        if (pendingStage ==
                            stage
                        ) {
                            Icon(
                                Icons.Outlined.CheckCircleOutline,
                                contentDescription = null,
                                tint = ZhiBanTerracotta,
                                modifier = Modifier.size(ZhiBanIconSize.Inline),
                            )
                        }
                    }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(pendingStage)
            }, enabled = pendingStage != currentStage) { Text("确认调整", color = ZhiBanTerracotta) }
        },
    )
}
