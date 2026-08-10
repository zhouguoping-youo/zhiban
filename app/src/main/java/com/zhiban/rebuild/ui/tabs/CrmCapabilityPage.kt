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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
    onAskAgent: (String) -> Unit,
    viewModel: CrmCapabilityViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val activeFormalLeads = state.leads.filter {
        it.status in
            setOf(CrmLeadStatus.NEW, CrmLeadStatus.CONTACTED, CrmLeadStatus.QUALIFIED)
    }
    val isWorkbenchEmpty = state.candidateLeads.isEmpty() && activeFormalLeads.isEmpty() &&
        state.opportunities.isEmpty() && state.actions.isEmpty() && state.suggestions.isEmpty()
    val priorities = remember(state) { buildCrmPriorities(state).take(1) }
    val openOpportunities = remember(state.opportunities) {
        state.opportunities.filter { it.entity.status == "OPEN" }
    }

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
                            if (!state.isDemo && !isWorkbenchEmpty) {
                                IconButton(
                                    onClick = { onAskAgent(newCrmOpportunityPrompt()) },
                                    modifier = Modifier.size(ZhiBanSize.TouchTarget),
                                ) {
                                    Icon(
                                        Icons.Outlined.Add,
                                        contentDescription = "新建机会",
                                        modifier = Modifier.size(ZhiBanIconSize.Action),
                                    )
                                }
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

            if (isWorkbenchEmpty) {
                item {
                    CrmEmptyWorkbench(
                        onCreateOpportunity = { onAskAgent(newCrmOpportunityPrompt()) },
                        modifier = Modifier.padding(horizontal = ZhiBanSpacing.PageHorizontal),
                    )
                }
            } else {
                item {
                    CrmWorkbenchSummary(
                        openOpportunityCount = openOpportunities.size,
                        pendingActionCount = state.actions.size,
                        candidateCount = state.candidateLeads.size,
                        modifier = Modifier.padding(horizontal = ZhiBanSpacing.PageHorizontal),
                    )
                }
            }

            if (!isWorkbenchEmpty && priorities.isNotEmpty()) {
                item {
                    CrmSectionHeader(
                        title = "现在最值得做",
                        modifier = Modifier.padding(horizontal = ZhiBanSpacing.PageHorizontal),
                    )
                }
                items(priorities, key = { "priority-${it.kind}-${it.opportunityId}-${it.candidateLeadId}" }) { priority ->
                    val opportunity = state.opportunities.firstOrNull {
                        it.entity.opportunityId == priority.opportunityId
                    }
                    CrmPriorityCard(
                        priority = priority,
                        primary = priority == priorities.first(),
                        onOpen = {
                            priority.opportunityId?.let(onOpenOpportunity) ?: onOpenLeads()
                        },
                        onPrepare = opportunity?.let {
                            {
                                onAskAgent(
                                    crmOpportunityCoachPrompt(
                                        opportunityId = it.entity.opportunityId,
                                        opportunityTitle = it.entity.title,
                                        guidanceTitle = priority.title,
                                        evidence = priority.reason,
                                        isDemo = state.isDemo,
                                    ),
                                )
                            }
                        },
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
                            title = "机会进展",
                            action = "查看全部",
                            onAction = { onOpenOpportunityList(null) },
                        )
                        CrmStageOverview(
                            opportunities = state.opportunities,
                            formalLeadCount = activeFormalLeads.size,
                            onStageClick = { onOpenOpportunityList(it) },
                        )
                    }
                }
                item {
                    CrmSectionHeader(
                        title = "进行中的机会",
                        action = if (state.candidateLeads.isNotEmpty() || activeFormalLeads.isNotEmpty()) "线索池" else null,
                        onAction = if (state.candidateLeads.isNotEmpty() || activeFormalLeads.isNotEmpty()) onOpenLeads else null,
                        modifier = Modifier.padding(horizontal = ZhiBanSpacing.PageHorizontal),
                    )
                }
            }

            if (!isWorkbenchEmpty && openOpportunities.isEmpty()) {
                item {
                    Text(
                        "暂无进行中的机会",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = ZhiBanSpacing.PageHorizontal),
                    )
                }
            }

            items(
                openOpportunities.take(4),
                key = { it.entity.opportunityId },
            ) { opportunity ->
                CrmOpportunityRow(
                    opportunity = opportunity,
                    statusLine = crmOpportunityStatusLine(opportunity, state.actions, System.currentTimeMillis()),
                    onClick = { onOpenOpportunity(opportunity.entity.opportunityId) },
                    modifier = Modifier.padding(horizontal = ZhiBanSpacing.PageHorizontal),
                )
            }
        }
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
