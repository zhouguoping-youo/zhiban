package com.zhiban.rebuild.ui.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhiban.rebuild.R
import com.zhiban.rebuild.data.crm.CrmOpportunityStage
import com.zhiban.rebuild.ui.components.ZhiBanChip
import com.zhiban.rebuild.ui.components.ZhiBanPage
import com.zhiban.rebuild.ui.components.ZhiBanTopBar
import com.zhiban.rebuild.ui.components.localizedQuantity
import com.zhiban.rebuild.ui.theme.ZhiBanSpacing

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CrmOpportunityListPage(
    initialStage: String?,
    onBack: () -> Unit,
    onOpenOpportunity: (String) -> Unit,
    onOpenBoard: () -> Unit,
    viewModel: CrmCapabilityViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var selectedStage by remember(initialStage) { mutableStateOf(initialStage) }
    val filters = remember {
        listOf<String?>(null) + crmVisibleStages + CrmOpportunityStage.LOST
    }
    val nowEpochMs = System.currentTimeMillis()
    val visible = remember(state.opportunities, state.actions, selectedStage) {
        state.opportunities.filter { selectedStage == null || it.entity.stage == selectedStage }.sortedWith(
            compareBy<CrmOpportunityUi> {
                val status = crmOpportunityStatusLine(it, state.actions, nowEpochMs)
                when {
                    status.startsWith("已逾期") -> 0
                    status == "尚未安排下一步" -> 1
                    else -> 2
                }
            }.thenByDescending { it.entity.updatedAtEpochMs },
        )
    }

    ZhiBanPage {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = ZhiBanSpacing.Xxxl),
            verticalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Md),
        ) {
            item {
                ZhiBanTopBar(
                    title = "机会",
                    subtitle = localizedQuantity(R.plurals.opportunity_count, visible.size),
                    onBack = onBack,
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = onOpenBoard, modifier = Modifier.testTag("crm-open-board")) { Text("看板") }
                            if (state.isDemo) {
                                CrmDemoBadge()
                                TextButton(onClick = viewModel::exitDemo) { Text("退出演示") }
                            } else {
                                TextButton(onClick = viewModel::enterDemo) { Text("查看演示") }
                            }
                        }
                    },
                )
            }
            item {
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = ZhiBanSpacing.PageHorizontal),
                    horizontalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Sm),
                    verticalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Sm),
                ) {
                    filters.forEach { stage ->
                        ZhiBanChip(
                            text = stage?.let(::crmStageLabel) ?: "全部",
                            selected = selectedStage == stage,
                            onClick = { selectedStage = stage },
                        )
                    }
                }
            }
            items(visible, key = { it.entity.opportunityId }) { opportunity ->
                CrmOpportunityRow(
                    opportunity = opportunity,
                    statusLine = crmOpportunityStatusLine(opportunity, state.actions, nowEpochMs),
                    onClick = { onOpenOpportunity(opportunity.entity.opportunityId) },
                    modifier = Modifier.padding(horizontal = ZhiBanSpacing.PageHorizontal),
                )
            }
        }
    }
}
