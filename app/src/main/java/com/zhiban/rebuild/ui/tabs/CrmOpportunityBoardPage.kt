package com.zhiban.rebuild.ui.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhiban.rebuild.data.crm.CrmOpportunityStage
import com.zhiban.rebuild.ui.components.ZhiBanPage
import com.zhiban.rebuild.ui.components.ZhiBanTopBar
import com.zhiban.rebuild.ui.components.zhiBanCardSurface
import com.zhiban.rebuild.ui.theme.ZhiBanSize
import com.zhiban.rebuild.ui.theme.ZhiBanSpacing
import com.zhiban.rebuild.ui.theme.ZhiBanTerracotta
import com.zhiban.rebuild.ui.theme.ZhiBanTerracottaSoft

@Composable
fun CrmOpportunityBoardPage(onBack: () -> Unit, onOpenOpportunity: (String) -> Unit, viewModel: CrmCapabilityViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val columns = remember(state.opportunities) {
        buildCrmBoardColumns(state.opportunities).filter { it.count > 0 }
    }

    ZhiBanPage {
        Column(Modifier.fillMaxSize()) {
            ZhiBanTopBar(title = "机会看板", subtitle = "${state.opportunities.size} 条机会", onBack = onBack)
            if (columns.isEmpty()) {
                CrmDetailPageState(title = "还没有机会", message = "先创建一条机会，再按阶段推进。")
            } else {
                CrmOpportunityBoardContent(
                    columns = columns,
                    onOpenOpportunity = onOpenOpportunity,
                    onAdvance = { stage, opportunityId ->
                        nextCrmBoardStage(stage)?.let { viewModel.changeStage(opportunityId, it) }
                    },
                )
            }
        }
    }
}

@Composable
internal fun CrmOpportunityBoardContent(columns: List<CrmBoardColumn>, onOpenOpportunity: (String) -> Unit, onAdvance: (String, String) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = ZhiBanSpacing.PageHorizontal, vertical = ZhiBanSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Lg),
    ) {
        items(columns, key = { it.stage }) { column ->
            CrmBoardColumnView(
                column = column,
                onOpenOpportunity = onOpenOpportunity,
                onAdvance = { opportunityId -> onAdvance(column.stage, opportunityId) },
            )
        }
    }
}

@Composable
internal fun CrmBoardColumnView(column: CrmBoardColumn, onOpenOpportunity: (String) -> Unit, onAdvance: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().testTag("crm-board-column-${column.stage}"),
        verticalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Sm),
    ) {
        Column(
            Modifier.fillMaxWidth().zhiBanCardSurface(ZhiBanTerracottaSoft).padding(ZhiBanSpacing.Md),
        ) {
            Text(
                crmStageLabel(column.stage),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "${column.count} 条 · ${formatCrmMoney(column.totalValueMinor, "CNY")}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Sm)) {
            column.opportunities.forEach { opportunity ->
                CrmBoardCard(
                    opportunity = opportunity,
                    isTerminal = column.isTerminal,
                    nextStageLabel = nextCrmBoardStage(column.stage)?.let(::crmStageLabel),
                    onOpen = { onOpenOpportunity(opportunity.entity.opportunityId) },
                    onAdvance = { onAdvance(opportunity.entity.opportunityId) },
                )
            }
        }
    }
}

@Composable
internal fun CrmBoardCard(
    opportunity: CrmOpportunityUi,
    isTerminal: Boolean,
    nextStageLabel: String?,
    onOpen: () -> Unit,
    onAdvance: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().testTag("crm-board-card-${opportunity.entity.opportunityId}")
            .zhiBanCardSurface().padding(ZhiBanSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Xs),
    ) {
        Text(
            opportunity.entity.title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "${opportunity.contactName} · ${opportunity.entity.accountNameSnapshot}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "${formatCrmMoney(opportunity.entity.valueMinor, opportunity.entity.currencyCode)} · " +
                "${opportunity.entity.probabilityPercent}%",
            style = MaterialTheme.typography.labelSmall,
            color = ZhiBanTerracotta,
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = onOpen,
                modifier = Modifier.weight(1f).defaultMinSize(minHeight = ZhiBanSize.TouchTarget),
            ) { Text("详情") }
            if (!isTerminal && nextStageLabel != null) {
                TextButton(
                    onClick = onAdvance,
                    modifier = Modifier.weight(1f).testTag("crm-board-advance-${opportunity.entity.opportunityId}")
                        .defaultMinSize(minHeight = ZhiBanSize.TouchTarget),
                ) { Text("→ $nextStageLabel", color = ZhiBanTerracotta) }
            }
        }
    }
}
