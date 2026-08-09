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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhiban.rebuild.data.crm.CrmLeadEntity
import com.zhiban.rebuild.data.crm.CrmLeadStatus
import com.zhiban.rebuild.data.crm.CrmSuggestionType
import com.zhiban.rebuild.ui.components.ZhiBanPage
import com.zhiban.rebuild.ui.components.ZhiBanTopBar
import com.zhiban.rebuild.ui.components.zhiBanCardSurface
import com.zhiban.rebuild.ui.theme.ZhiBanIconSize
import com.zhiban.rebuild.ui.theme.ZhiBanSize
import com.zhiban.rebuild.ui.theme.ZhiBanSpacing
import com.zhiban.rebuild.ui.theme.ZhiBanTerracotta
import com.zhiban.rebuild.ui.theme.ZhiBanTerracottaSoft

@Composable
fun CrmCapabilityPage(
    onBack: () -> Unit,
    onOpenLeads: () -> Unit,
    onOpenOpportunityList: (String?) -> Unit,
    onOpenOpportunity: (String) -> Unit,
    onOpenCalendar: (Long?) -> Unit,
    onAskAgent: (String) -> Unit,
    viewModel: CrmCapabilityViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var candidateToPromote by remember { mutableStateOf<com.zhiban.rebuild.data.crm.CrmLeadEntity?>(null) }
    val activeFormalLeads = state.leads.filter {
        it.status in
            setOf(CrmLeadStatus.NEW, CrmLeadStatus.CONTACTED, CrmLeadStatus.QUALIFIED)
    }
    val isWorkbenchEmpty = state.candidateLeads.isEmpty() && activeFormalLeads.isEmpty() &&
        state.opportunities.isEmpty() && state.actions.isEmpty() && state.suggestions.isEmpty()

    ZhiBanPage {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = ZhiBanSpacing.Xxxl),
            verticalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Section),
        ) {
            item {
                ZhiBanTopBar(
                    title = "个人 CRM",
                    onBack = onBack,
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (state.isDemo) {
                                CrmDemoBadge()
                                TextButton(onClick = viewModel::exitDemo) { Text("退出演示") }
                            } else {
                                TextButton(onClick = viewModel::enterDemo) { Text("查看演示") }
                            }
                            IconButton(
                                onClick = { onAskAgent(newCrmOpportunityPrompt()) },
                                enabled = !state.isDemo,
                                modifier = Modifier.size(ZhiBanSize.TouchTarget),
                            ) {
                                Icon(
                                    Icons.Outlined.Add,
                                    contentDescription = if (state.isDemo) "退出演示后新建机会" else "新建机会",
                                    modifier = Modifier.size(ZhiBanIconSize.Action),
                                )
                            }
                        }
                    },
                )
            }

            state.demoNotice?.let { notice ->
                item {
                    Row(
                        Modifier.padding(
                            horizontal = ZhiBanSpacing.PageHorizontal,
                        ).fillMaxWidth().zhiBanCardSurface(ZhiBanTerracottaSoft)
                            .clickable(onClick = viewModel::clearDemoNotice).padding(ZhiBanSpacing.Lg),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            notice,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            if (!state.isDemo && state.candidateLeads.isNotEmpty()) {
                item {
                    CrmSectionHeader(
                        title = "知伴发现的候选线索",
                        action = "线索池",
                        onAction = onOpenLeads,
                        modifier = Modifier.padding(horizontal = ZhiBanSpacing.PageHorizontal),
                    )
                }
                items(state.candidateLeads, key = { "candidate-${it.leadId}" }) { lead ->
                    CrmCandidateLeadCard(
                        lead = lead,
                        onPromote = { candidateToPromote = lead },
                        onIgnore = { viewModel.ignoreCandidateLead(lead.leadId) },
                        modifier = Modifier.padding(horizontal = ZhiBanSpacing.PageHorizontal),
                    )
                }
            }

            if (activeFormalLeads.isNotEmpty()) {
                item {
                    CrmSectionHeader(
                        title = "正式线索",
                        subtitle = "已由你确认，参与推进判断",
                        action = "线索池",
                        onAction = onOpenLeads,
                        modifier = Modifier.padding(horizontal = ZhiBanSpacing.PageHorizontal),
                    )
                }
                items(activeFormalLeads, key = { "formal-${it.leadId}" }) { lead ->
                    CrmFormalLeadCard(
                        lead = lead,
                        modifier = Modifier.padding(horizontal = ZhiBanSpacing.PageHorizontal),
                    )
                }
            }

            if (isWorkbenchEmpty) {
                item {
                    CrmEmptyWorkbench(
                        onCreateOpportunity = { onAskAgent(newCrmOpportunityPrompt()) },
                        modifier = Modifier.padding(horizontal = ZhiBanSpacing.PageHorizontal),
                    )
                }
            } else if (!state.dashboard.isEmpty) {
                item {
                    CrmDashboardSummaryRow(
                        dashboard = state.dashboard,
                        modifier = Modifier.padding(horizontal = ZhiBanSpacing.PageHorizontal),
                    )
                }
            }

            if (!isWorkbenchEmpty) {
                item {
                    Column(
                        Modifier.padding(horizontal = ZhiBanSpacing.PageHorizontal),
                        verticalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Lg),
                    ) {
                        CrmSectionHeader(
                            title = "推进概览",
                            action = "全部",
                            onAction = { onOpenOpportunityList(null) },
                        )
                        CrmStageOverview(
                            opportunities = state.opportunities,
                            formalLeadCount = activeFormalLeads.size,
                            onStageClick = { onOpenOpportunityList(it) },
                        )
                    }
                }
            }

            if (!isWorkbenchEmpty) {
                item {
                    CrmFollowUpSection(
                        followUps = state.followUps,
                        onOpenOpportunity = onOpenOpportunity,
                        onOpenCalendar = onOpenCalendar,
                        modifier = Modifier.padding(horizontal = ZhiBanSpacing.PageHorizontal),
                    )
                }
            }

            if (!isWorkbenchEmpty) {
                item {
                    CrmSectionHeader(
                        title = "今日推进",
                        action = state.followUps.unscheduled.takeIf { it.isNotEmpty() }?.let { "日历" },
                        onAction = state.followUps.unscheduled.takeIf { it.isNotEmpty() }?.let { { onOpenCalendar(null) } },
                        modifier = Modifier.padding(horizontal = ZhiBanSpacing.PageHorizontal),
                    )
                }
            }

            if (!isWorkbenchEmpty && state.followUps.unscheduled.isEmpty()) {
                item {
                    Text(
                        "暂无待办",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = ZhiBanSpacing.PageHorizontal),
                    )
                }
            } else if (!isWorkbenchEmpty) {
                items(state.followUps.unscheduled.take(3), key = { it.entity.actionId }) { action ->
                    CrmActionRow(
                        action = action,
                        onOpenOpportunity = { onOpenOpportunity(action.entity.opportunityId) },
                        onOpenCalendar = { onOpenCalendar(action.entity.dueAtEpochMs) },
                        modifier = Modifier.padding(horizontal = ZhiBanSpacing.PageHorizontal),
                    )
                }
            }

            if (state.suggestions.isNotEmpty()) {
                item {
                    CrmSectionHeader(
                        title = "需要你判断",
                        modifier = Modifier.padding(horizontal = ZhiBanSpacing.PageHorizontal),
                    )
                }
                items(state.suggestions.take(2), key = { it.entity.suggestionId }) { suggestion ->
                    CrmSuggestionCard(
                        suggestion = suggestion,
                        onOpenOpportunity = { suggestion.entity.opportunityId?.let(onOpenOpportunity) },
                        onAccept = when (suggestion.entity.suggestionType) {
                            CrmSuggestionType.CALL_FOLLOW_UP -> {
                                { viewModel.acceptCallFollowUpSuggestion(suggestion.entity.suggestionId) }
                            }

                            CrmSuggestionType.NEW_LEAD -> {
                                { viewModel.acceptNewLeadSuggestion(suggestion.entity.suggestionId) }
                            }

                            else -> null
                        },
                        onAskAgent = {
                            onAskAgent(
                                crmSuggestionPrompt(
                                    opportunityId = suggestion.entity.opportunityId,
                                    suggestionTitle = suggestion.entity.title,
                                    isDemo = state.isDemo,
                                ),
                            )
                        },
                        onDismiss = { viewModel.dismissSuggestion(suggestion.entity.suggestionId) },
                        modifier = Modifier.padding(horizontal = ZhiBanSpacing.PageHorizontal),
                    )
                }
            }

            if (!isWorkbenchEmpty) {
                item {
                    CrmSectionHeader(
                        title = "进行中的机会",
                        action = "全部",
                        onAction = { onOpenOpportunityList(null) },
                        modifier = Modifier.padding(horizontal = ZhiBanSpacing.PageHorizontal),
                    )
                }
            }

            items(
                state.opportunities.filter {
                    it.entity.status == "OPEN"
                }.take(4),
                key = { it.entity.opportunityId },
            ) { opportunity ->
                CrmOpportunityRow(
                    opportunity = opportunity,
                    onClick = { onOpenOpportunity(opportunity.entity.opportunityId) },
                    modifier = Modifier.padding(horizontal = ZhiBanSpacing.PageHorizontal),
                )
            }
        }
    }

    candidateToPromote?.let { lead ->
        AlertDialog(
            onDismissRequest = { candidateToPromote = null },
            title = { Text("转为正式线索？") },
            text = { Text("${lead.displayNameSnapshot} 将进入正式线索列表，之后可参与个人 CRM 的推进判断。") },
            dismissButton = { TextButton(onClick = { candidateToPromote = null }) { Text("取消") } },
            confirmButton = {
                TextButton(onClick = {
                    candidateToPromote = null
                    viewModel.promoteCandidateLead(lead.leadId)
                }) { Text("确认转正", color = ZhiBanTerracotta) }
            },
        )
    }
}

@Composable
internal fun CrmEmptyWorkbench(onCreateOpportunity: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .testTag("crm-empty-workbench")
            .zhiBanCardSurface()
            .padding(horizontal = ZhiBanSpacing.Lg, vertical = ZhiBanSpacing.Xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Md),
    ) {
        Box(
            Modifier.size(ZhiBanSize.TouchTarget).clip(CircleShape).background(ZhiBanTerracottaSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.AutoAwesome,
                contentDescription = null,
                tint = ZhiBanTerracotta,
                modifier = Modifier.size(ZhiBanIconSize.Leading),
            )
        }
        Text(
            "还没有客户进展",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            "从一个机会开始",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FilledTonalButton(
            onClick = onCreateOpportunity,
            modifier = Modifier.defaultMinSize(minHeight = ZhiBanSize.TouchTarget).testTag("crm-empty-create"),
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = ZhiBanTerracottaSoft,
                contentColor = ZhiBanTerracotta,
            ),
        ) {
            Text("新建机会", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
internal fun CrmCandidateLeadCard(
    lead: com.zhiban.rebuild.data.crm.CrmLeadEntity,
    onPromote: () -> Unit,
    onIgnore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().testTag(
            "crm-candidate-${lead.leadId}",
        ).zhiBanCardSurface(ZhiBanTerracottaSoft).padding(ZhiBanSpacing.Lg),
        verticalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    lead.displayNameSnapshot,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    listOfNotNull(lead.companyNameSnapshot, lead.fitSummary).joinToString(" · ").ifBlank {
                        "等待你判断是否值得跟进"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text("候选", style = MaterialTheme.typography.labelMedium, color = ZhiBanTerracotta)
        }
        Text(
            "知伴判断 ${(lead.confidence * 100).toInt()}%",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(
                onClick = onIgnore,
                modifier = Modifier.testTag(
                    "crm-candidate-ignore-${lead.leadId}",
                ).defaultMinSize(minHeight = ZhiBanSize.TouchTarget),
            ) {
                Text("忽略")
            }
            TextButton(
                onClick = onPromote,
                modifier = Modifier.testTag(
                    "crm-candidate-promote-${lead.leadId}",
                ).defaultMinSize(minHeight = ZhiBanSize.TouchTarget),
            ) {
                Text("转为正式线索", color = ZhiBanTerracotta)
            }
        }
    }
}

@Composable
internal fun CrmFormalLeadCard(lead: CrmLeadEntity, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .testTag("crm-formal-lead-${lead.leadId}")
            .zhiBanCardSurface()
            .padding(ZhiBanSpacing.Lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Md),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Xs)) {
            Text(
                lead.displayNameSnapshot,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                listOfNotNull(lead.companyNameSnapshot, lead.fitSummary).joinToString(" · ").ifBlank { "等待下一步推进" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            when (lead.status) {
                CrmLeadStatus.CONTACTED -> "已联系"
                CrmLeadStatus.QUALIFIED -> "已确认需求"
                else -> "线索"
            },
            style = MaterialTheme.typography.labelMedium,
            color = ZhiBanTerracotta,
        )
    }
}

@Composable
private fun CrmSuggestionCard(
    suggestion: CrmSuggestionUi,
    onOpenOpportunity: () -> Unit,
    onAccept: (() -> Unit)?,
    onAskAgent: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().zhiBanCardSurface(ZhiBanTerracottaSoft).padding(ZhiBanSpacing.Lg),
        verticalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(ZhiBanTerracotta),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Lightbulb,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(ZhiBanIconSize.Leading),
                )
            }
            Spacer(Modifier.width(ZhiBanSpacing.Md))
            Column(Modifier.weight(1f)) {
                Text(
                    suggestion.entity.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    suggestion.subtitle(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(ZhiBanIconSize.Inline),
            )
        }
        Text(
            suggestion.entity.summary,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            "依据：${suggestion.entity.rationale}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onDismiss, modifier = Modifier.defaultMinSize(minHeight = ZhiBanSize.TouchTarget)) {
                Text("忽略", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (suggestion.entity.opportunityId != null) {
                TextButton(
                    onClick = onOpenOpportunity,
                    modifier = Modifier.defaultMinSize(minHeight = ZhiBanSize.TouchTarget),
                ) {
                    Text("查看机会", color = MaterialTheme.colorScheme.onSurface)
                }
            }
            if (onAccept != null) {
                TextButton(
                    onClick = onAccept,
                    modifier = Modifier.testTag("crm-suggestion-accept-${suggestion.entity.suggestionId}")
                        .defaultMinSize(minHeight = ZhiBanSize.TouchTarget),
                ) {
                    Text(
                        if (suggestion.entity.suggestionType == CrmSuggestionType.NEW_LEAD) "加为线索" else "记录跟进",
                        color = ZhiBanTerracotta,
                    )
                }
            }
            TextButton(onClick = onAskAgent, modifier = Modifier.defaultMinSize(minHeight = ZhiBanSize.TouchTarget)) {
                Icon(
                    Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(ZhiBanIconSize.Inline),
                )
                Spacer(Modifier.width(ZhiBanSpacing.Xs))
                Text("问问知伴", color = ZhiBanTerracotta)
            }
        }
    }
}

/** Context line under the suggestion title: "account · opportunity", or the contact name for NEW_LEAD. */
private fun CrmSuggestionUi.subtitle(): String = when {
    accountName != null && opportunityTitle != null -> "$accountName · $opportunityTitle"
    opportunityTitle != null -> opportunityTitle
    accountName != null -> accountName
    else -> contactName ?: ""
}
