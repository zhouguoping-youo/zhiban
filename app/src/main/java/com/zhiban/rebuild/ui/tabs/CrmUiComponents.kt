package com.zhiban.rebuild.ui.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhiban.rebuild.data.crm.CrmOpportunityStage
import com.zhiban.rebuild.ui.components.zhiBanCardSurface
import com.zhiban.rebuild.ui.theme.ZhiBanIconSize
import com.zhiban.rebuild.ui.theme.ZhiBanRadius
import com.zhiban.rebuild.ui.theme.ZhiBanSize
import com.zhiban.rebuild.ui.theme.ZhiBanSpacing
import com.zhiban.rebuild.ui.theme.ZhiBanTerracotta
import com.zhiban.rebuild.ui.theme.ZhiBanTerracottaSoft
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal val crmVisibleStages = listOf(
    CrmOpportunityStage.LEAD,
    CrmOpportunityStage.CONTACTED,
    CrmOpportunityStage.QUALIFIED,
    CrmOpportunityStage.PROPOSAL,
    CrmOpportunityStage.NEGOTIATION,
    CrmOpportunityStage.WON,
)

internal fun crmStageLabel(stage: String): String = when (stage) {
    CrmOpportunityStage.LEAD -> "线索"
    CrmOpportunityStage.CONTACTED -> "已联系"
    CrmOpportunityStage.QUALIFIED -> "已确认需求"
    CrmOpportunityStage.PROPOSAL -> "方案/报价"
    CrmOpportunityStage.NEGOTIATION -> "商务推进"
    CrmOpportunityStage.WON -> "成交"
    CrmOpportunityStage.LOST -> "流失"
    else -> "未设置"
}

internal fun crmStakeholderRoleLabel(role: String): String = when (role) {
    "DECISION_MAKER" -> "决策人"
    "CHAMPION" -> "内部推动者"
    "USER" -> "使用人"
    "INFLUENCER" -> "影响人"
    else -> "相关人"
}

internal fun formatCrmMoney(valueMinor: Long?, currencyCode: String): String {
    if (valueMinor == null) return "金额待确认"
    val amount = valueMinor / 100.0
    val prefix = if (currencyCode == "CNY") "¥" else "$currencyCode "
    return prefix + NumberFormat.getNumberInstance(Locale.CHINA).apply { maximumFractionDigits = 0 }.format(amount)
}

private val crmDateTimeFormatter = DateTimeFormatter.ofPattern("M月d日 E HH:mm", Locale.CHINA)
private val crmDateFormatter = DateTimeFormatter.ofPattern("M月d日", Locale.CHINA)

/** Warning tone for overdue follow-ups; readable on both light and dark surfaces. */
internal val CrmOverdueColor = Color(0xFFB3261E)
internal val CrmOverdueSoft = Color(0x1AB3261E)

internal fun formatCrmDateTime(epochMs: Long?): String = epochMs?.let {
    Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(crmDateTimeFormatter)
} ?: "时间待确认"

internal fun formatCrmDate(epochMs: Long?): String = epochMs?.let {
    Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(crmDateFormatter)
} ?: "待确认"

/** One board column: a stage plus its opportunities and aggregate count/value. */
internal data class CrmBoardColumn(val stage: String, val opportunities: List<CrmOpportunityUi>, val totalValueMinor: Long) {
    val count: Int get() = opportunities.size
    val isTerminal: Boolean get() = stage in CrmOpportunityStage.terminalStages
}

/** Groups opportunities into the 7 board columns in stage order, summing each column's value. */
internal fun buildCrmBoardColumns(opportunities: List<CrmOpportunityUi>): List<CrmBoardColumn> = CrmOpportunityStage.allStages.map { stage ->
    val columnOpportunities = opportunities.filter { it.entity.stage == stage }
    CrmBoardColumn(
        stage = stage,
        opportunities = columnOpportunities,
        totalValueMinor = columnOpportunities.mapNotNull { it.entity.valueMinor }.sum(),
    )
}

/** The stage a board move action targets, or null when the current stage is terminal/last. */
internal fun nextCrmBoardStage(stage: String): String? {
    if (stage in CrmOpportunityStage.terminalStages) return null
    val index = CrmOpportunityStage.activeStages.indexOf(stage)
    if (index < 0) return null
    return CrmOpportunityStage.activeStages.getOrNull(index + 1) ?: CrmOpportunityStage.WON
}

@Composable
internal fun CrmSectionHeader(title: String, modifier: Modifier = Modifier, subtitle: String? = null, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            subtitle?.takeIf(String::isNotBlank)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (action != null && onAction != null) {
            Box(
                Modifier.defaultMinSize(
                    minWidth = ZhiBanSize.TouchTarget,
                    minHeight = ZhiBanSize.TouchTarget,
                ).clickable(onClick = onAction),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Text(action, style = MaterialTheme.typography.labelLarge, color = ZhiBanTerracotta)
            }
        }
    }
}

@Composable
internal fun CrmStageOverview(opportunities: List<CrmOpportunityUi>, formalLeadCount: Int = 0, onStageClick: (String) -> Unit, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val columnCount = if (maxWidth >= 600.dp) crmVisibleStages.size else 3
        val rows = crmVisibleStages.chunked(columnCount)
        Column(Modifier.fillMaxWidth().zhiBanCardSurface()) {
            rows.forEachIndexed { rowIndex, stages ->
                if (rowIndex > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    stages.forEachIndexed { index, stage ->
                        val count = opportunities.count { it.entity.stage == stage } +
                            if (stage == CrmOpportunityStage.LEAD) formalLeadCount else 0
                        Column(
                            Modifier
                                .weight(1f)
                                .testTag("crm-stage-$stage-count-$count")
                                .defaultMinSize(minHeight = 76.dp)
                                .clickable { onStageClick(stage) }
                                .padding(horizontal = ZhiBanSpacing.Md, vertical = ZhiBanSpacing.Sm),
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                count.toString(),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = if (count > 0) ZhiBanTerracotta else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                crmStageLabel(stage),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (index < stages.lastIndex) {
                            Box(Modifier.width(1.dp).height(44.dp).background(MaterialTheme.colorScheme.outlineVariant))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CrmStatChip(value: String, label: String, modifier: Modifier = Modifier, valueColor: Color = ZhiBanTerracotta) {
    Column(
        modifier.defaultMinSize(minHeight = 56.dp).padding(horizontal = ZhiBanSpacing.Md, vertical = ZhiBanSpacing.Sm),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Top-of-page dashboard: real aggregate counters only (open pipeline value, won/lost, recent
 * leads/activities, due follow-ups). Renders nothing when there is no data to report.
 */
@Composable
internal fun CrmDashboardSummaryRow(dashboard: CrmDashboardUi, modifier: Modifier = Modifier) {
    if (dashboard.isEmpty) return
    Column(modifier.fillMaxWidth()) {
        CrmSectionHeader(title = "经营概览", subtitle = "近 7 天", modifier = Modifier.padding(bottom = ZhiBanSpacing.Sm))
        Row(
            Modifier.fillMaxWidth().zhiBanCardSurface().padding(vertical = ZhiBanSpacing.Xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CrmStatChip(
                value = formatCrmMoney(dashboard.openOpportunityValueMinor, "CNY"),
                label = "在管金额",
                modifier = Modifier.weight(1f).testTag("crm-dash-open-value"),
            )
            CrmStatChip(
                value = "${dashboard.wonOpportunityCount}/${dashboard.lostOpportunityCount}",
                label = "成交/流失",
                modifier = Modifier.weight(1f).testTag("crm-dash-won-lost"),
            )
            CrmStatChip(
                value = dashboard.newLeadsCount.toString(),
                label = "新线索",
                modifier = Modifier.weight(1f).testTag("crm-dash-new-leads"),
            )
            val attention = dashboard.overdueActionCount + dashboard.dueTodayActionCount
            CrmStatChip(
                value = attention.toString(),
                label = "待跟进",
                valueColor = if (dashboard.overdueActionCount > 0) CrmOverdueColor else ZhiBanTerracotta,
                modifier = Modifier.weight(1f).testTag("crm-dash-attention"),
            )
        }
    }
}

/**
 * Follow-up reminders bucketed into overdue / due-today / upcoming. Overdue rows are highlighted so
 * the user sees what needs attention first. Unscheduled actions stay in the plain list below.
 */
@Composable
internal fun CrmFollowUpSection(
    followUps: CrmFollowUpGroups,
    onOpenOpportunity: (String) -> Unit,
    onOpenCalendar: (Long?) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (followUps.overdue.isEmpty() && followUps.dueToday.isEmpty()) return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Lg)) {
        CrmSectionHeader(
            title = "跟进提醒",
            subtitle = "按截止时间的逾期与今日待办",
        )
        followUps.overdue.forEach { action ->
            CrmFollowUpRow(
                action = action,
                dueLabel = "已逾期 · ${formatCrmDateTime(action.entity.dueAtEpochMs)}",
                overdue = true,
                onOpenOpportunity = { onOpenOpportunity(action.entity.opportunityId) },
                onOpenCalendar = { onOpenCalendar(action.entity.dueAtEpochMs) },
            )
        }
        followUps.dueToday.forEach { action ->
            CrmFollowUpRow(
                action = action,
                dueLabel = "今天 · ${formatCrmDateTime(action.entity.dueAtEpochMs)}",
                overdue = false,
                onOpenOpportunity = { onOpenOpportunity(action.entity.opportunityId) },
                onOpenCalendar = { onOpenCalendar(action.entity.dueAtEpochMs) },
            )
        }
    }
}

@Composable
private fun CrmFollowUpRow(
    action: CrmActionUi,
    dueLabel: String,
    overdue: Boolean,
    onOpenOpportunity: () -> Unit,
    onOpenCalendar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().defaultMinSize(minHeight = 72.dp)
            .zhiBanCardSurface(if (overdue) CrmOverdueSoft else ZhiBanTerracottaSoft)
            .clickable(onClick = onOpenOpportunity)
            .padding(start = ZhiBanSpacing.Lg, top = ZhiBanSpacing.Md, bottom = ZhiBanSpacing.Md, end = ZhiBanSpacing.Sm)
            .testTag("crm-followup-${action.entity.actionId}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(if (overdue) CrmOverdueSoft else ZhiBanTerracottaSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Schedule,
                contentDescription = null,
                tint = if (overdue) CrmOverdueColor else ZhiBanTerracotta,
                modifier = Modifier.size(ZhiBanIconSize.Leading),
            )
        }
        Spacer(Modifier.width(ZhiBanSpacing.Md))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                action.entity.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${action.contactName} · ${action.opportunityTitle}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                dueLabel,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (overdue) FontWeight.SemiBold else FontWeight.Normal,
                color = if (overdue) CrmOverdueColor else ZhiBanTerracotta,
            )
        }
        IconButton(onClick = onOpenCalendar, modifier = Modifier.size(ZhiBanSize.TouchTarget)) {
            Icon(
                Icons.Outlined.CalendarMonth,
                contentDescription = "在日历查看",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(ZhiBanIconSize.Action),
            )
        }
    }
}

@Composable
internal fun CrmStageProgress(currentStage: String, modifier: Modifier = Modifier) {
    val currentIndex = crmVisibleStages.indexOf(currentStage)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val columnCount = if (maxWidth >= 600.dp) crmVisibleStages.size else 3
        val rows = crmVisibleStages.chunked(columnCount)
        Column(Modifier.fillMaxWidth().zhiBanCardSurface()) {
            rows.forEachIndexed { rowIndex, stages ->
                if (rowIndex > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    stages.forEachIndexed { indexInRow, stage ->
                        val stageIndex = crmVisibleStages.indexOf(stage)
                        val completed = currentIndex >= 0 && stageIndex <= currentIndex
                        val current = stage == currentStage
                        Column(
                            Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 84.dp)
                                .padding(horizontal = ZhiBanSpacing.Sm, vertical = ZhiBanSpacing.Md),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Xs, Alignment.CenterVertically),
                        ) {
                            Box(
                                Modifier.size(28.dp).clip(CircleShape)
                                    .background(
                                        if (completed) ZhiBanTerracotta else MaterialTheme.colorScheme.surfaceVariant,
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (completed) {
                                    Text(
                                        "✓",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                    )
                                } else {
                                    Text(
                                        (stageIndex + 1).toString(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Text(
                                crmStageLabel(stage),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (current) ZhiBanTerracotta else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (indexInRow < stages.lastIndex) {
                            Box(Modifier.width(1.dp).height(44.dp).background(MaterialTheme.colorScheme.outlineVariant))
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun CrmOpportunityRow(opportunity: CrmOpportunityUi, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().defaultMinSize(minHeight = 88.dp).zhiBanCardSurface().clickable(onClick = onClick)
            .padding(horizontal = ZhiBanSpacing.Lg, vertical = ZhiBanSpacing.Md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(ZhiBanTerracottaSoft),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                opportunity.contactName.take(1),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = ZhiBanTerracotta,
            )
        }
        Spacer(Modifier.width(ZhiBanSpacing.Md))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    opportunity.entity.title,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    crmStageLabel(opportunity.entity.stage),
                    style = MaterialTheme.typography.labelSmall,
                    color = ZhiBanTerracotta,
                )
            }
            Text(
                "${opportunity.contactName} · ${opportunity.entity.accountNameSnapshot}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${formatCrmMoney(
                    opportunity.entity.valueMinor,
                    opportunity.entity.currencyCode,
                )} · 预计 ${formatCrmDate(opportunity.entity.expectedCloseAtEpochMs)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(ZhiBanSpacing.Sm))
        Icon(
            Icons.Outlined.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(ZhiBanIconSize.Inline),
        )
    }
}

@Composable
internal fun CrmActionRow(action: CrmActionUi, onOpenOpportunity: () -> Unit, onOpenCalendar: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().defaultMinSize(
            minHeight = 88.dp,
        ).zhiBanCardSurface().clickable(onClick = onOpenOpportunity)
            .padding(
                start = ZhiBanSpacing.Lg,
                top = ZhiBanSpacing.Md,
                bottom = ZhiBanSpacing.Md,
                end = ZhiBanSpacing.Sm,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(ZhiBanTerracottaSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Schedule,
                contentDescription = null,
                tint = ZhiBanTerracotta,
                modifier = Modifier.size(ZhiBanIconSize.Leading),
            )
        }
        Spacer(Modifier.width(ZhiBanSpacing.Md))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                action.entity.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${action.contactName} · ${action.opportunityTitle}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                formatCrmDateTime(action.entity.dueAtEpochMs),
                style = MaterialTheme.typography.bodySmall,
                color = ZhiBanTerracotta,
            )
        }
        IconButton(onClick = onOpenCalendar, modifier = Modifier.size(ZhiBanSize.TouchTarget)) {
            Icon(
                Icons.Outlined.CalendarMonth,
                contentDescription = "在日历查看",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(ZhiBanIconSize.Action),
            )
        }
    }
}

@Composable
internal fun CrmDemoBadge(modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(ZhiBanRadius.Full))
            .background(ZhiBanTerracottaSoft)
            .padding(horizontal = ZhiBanSpacing.Md, vertical = ZhiBanSpacing.Xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "演示数据",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = ZhiBanTerracotta,
        )
    }
}
