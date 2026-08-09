package com.zhiban.rebuild.ui.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.zhiban.rebuild.ui.components.zhiBanCardSurface
import com.zhiban.rebuild.ui.theme.ZhiBanIconSize
import com.zhiban.rebuild.ui.theme.ZhiBanSize
import com.zhiban.rebuild.ui.theme.ZhiBanSpacing
import com.zhiban.rebuild.ui.theme.ZhiBanTerracotta
import com.zhiban.rebuild.ui.theme.ZhiBanTerracottaSoft

@Composable
internal fun CrmWorkbenchSummary(openOpportunityCount: Int, pendingActionCount: Int, candidateCount: Int, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().zhiBanCardSurface().padding(vertical = ZhiBanSpacing.Lg),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        CrmSummaryItem("机会", openOpportunityCount)
        CrmSummaryItem("待办", pendingActionCount)
        CrmSummaryItem("候选", candidateCount)
    }
}

@Composable
private fun CrmSummaryItem(label: String, count: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            count.toString(),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun CrmPriorityCard(priority: CrmPriorityUi, primary: Boolean, onOpen: () -> Unit, onPrepare: (() -> Unit)?, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().testTag("crm-priority-${priority.kind.name.lowercase()}")
            .zhiBanCardSurface(if (primary) ZhiBanTerracottaSoft else MaterialTheme.colorScheme.surface)
            .clickable(onClick = onOpen).padding(ZhiBanSpacing.Lg),
        verticalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(ZhiBanSize.TouchTarget).clip(CircleShape)
                    .background(if (primary) ZhiBanTerracotta else MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (primary) Icons.Outlined.AutoAwesome else Icons.Outlined.Schedule,
                    contentDescription = null,
                    tint = if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(ZhiBanIconSize.Leading),
                )
            }
            Spacer(Modifier.width(ZhiBanSpacing.Md))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Xs)) {
                Text(
                    priority.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                priority.context.takeIf(String::isNotBlank)?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(ZhiBanIconSize.Inline),
            )
        }
        Text(
            "依据：${priority.reason}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (primary && onPrepare != null) {
            FilledTonalButton(
                onClick = onPrepare,
                modifier = Modifier.defaultMinSize(minHeight = ZhiBanSize.TouchTarget)
                    .testTag("crm-priority-prepare"),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = ZhiBanTerracotta,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(ZhiBanIconSize.Inline))
                Spacer(Modifier.width(ZhiBanSpacing.Sm))
                Text("让知伴准备")
            }
        }
    }
}

@Composable
internal fun CrmOpportunityGuidanceCard(guidance: CrmOpportunityGuidanceUi, onPrepare: () -> Unit, onCalendar: (() -> Unit)?, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().testTag("crm-opportunity-guidance")
            .zhiBanCardSurface(ZhiBanTerracottaSoft).padding(ZhiBanSpacing.Lg),
        verticalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Md),
    ) {
        Text("知伴建议", style = MaterialTheme.typography.labelLarge, color = ZhiBanTerracotta)
        Text(guidance.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(guidance.summary, style = MaterialTheme.typography.bodyMedium)
        Text(
            "依据：${guidance.evidence}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (onCalendar != null) {
                TextButton(onClick = onCalendar, modifier = Modifier.defaultMinSize(minHeight = ZhiBanSize.TouchTarget)) {
                    Text("查看日历")
                }
            }
            FilledTonalButton(
                onClick = onPrepare,
                modifier = Modifier.defaultMinSize(minHeight = ZhiBanSize.TouchTarget)
                    .testTag("crm-opportunity-guidance-prepare"),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = ZhiBanTerracotta,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Text("让知伴准备")
            }
        }
    }
}

@Composable
internal fun CrmCompactStageStatus(stage: String, onAdjust: (() -> Unit)?, modifier: Modifier = Modifier) {
    val nextStage = nextCrmBoardStage(stage)
    Row(
        modifier.fillMaxWidth().zhiBanCardSurface().padding(ZhiBanSpacing.Lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Xs)) {
            Text("当前阶段", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(crmStageLabel(stage), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (nextStage != null) {
                Text(
                    "下一阶段：${crmStageLabel(nextStage)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (onAdjust != null) {
            TextButton(onClick = onAdjust, modifier = Modifier.defaultMinSize(minHeight = ZhiBanSize.TouchTarget)) {
                Text("调整", color = ZhiBanTerracotta)
            }
        }
    }
}
