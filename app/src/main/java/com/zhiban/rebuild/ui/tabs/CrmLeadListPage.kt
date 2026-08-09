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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhiban.rebuild.data.agent.CrmLeadConversionInput
import com.zhiban.rebuild.data.crm.CrmLeadEntity
import com.zhiban.rebuild.data.crm.CrmLeadStatus
import com.zhiban.rebuild.ui.components.ZhiBanPage
import com.zhiban.rebuild.ui.components.ZhiBanTopBar
import com.zhiban.rebuild.ui.components.zhiBanCardSurface
import com.zhiban.rebuild.ui.theme.ZhiBanSize
import com.zhiban.rebuild.ui.theme.ZhiBanSpacing
import com.zhiban.rebuild.ui.theme.ZhiBanTerracotta
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun CrmLeadListPage(onBack: () -> Unit, onOpenOpportunity: (String) -> Unit, viewModel: CrmCapabilityViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var leadToConvert by remember { mutableStateOf<CrmLeadEntity?>(null) }
    var candidateToPromote by remember { mutableStateOf<CrmLeadEntity?>(null) }
    val formalLeads = state.leads.filter { it.status in FORMAL_LEAD_STATUSES }

    ZhiBanPage {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = ZhiBanSpacing.Xxxl),
            verticalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Md),
        ) {
            item {
                ZhiBanTopBar(
                    title = "线索池",
                    subtitle = "${formalLeads.size} 条正式线索 · ${state.candidateLeads.size} 条候选",
                    onBack = onBack,
                )
            }

            if (state.candidateLeads.isNotEmpty()) {
                item {
                    CrmSectionHeader(
                        title = "知伴发现的候选线索",
                        subtitle = "确认后转为正式线索",
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

            item {
                CrmSectionHeader(
                    title = "正式线索",
                    subtitle = "可标记需求、转化为商机或放弃",
                    modifier = Modifier.padding(horizontal = ZhiBanSpacing.PageHorizontal),
                )
            }
            if (formalLeads.isEmpty()) {
                item {
                    Text(
                        "还没有正式线索",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = ZhiBanSpacing.PageHorizontal),
                    )
                }
            } else {
                items(formalLeads, key = { "formal-${it.leadId}" }) { lead ->
                    CrmLeadPoolCard(
                        lead = lead,
                        onQualify = { viewModel.qualifyLead(lead.leadId) },
                        onConvert = { leadToConvert = lead },
                        onDisqualify = { viewModel.disqualifyLead(lead.leadId) },
                        modifier = Modifier.padding(horizontal = ZhiBanSpacing.PageHorizontal),
                    )
                }
            }
        }
    }

    candidateToPromote?.let { lead ->
        AlertDialog(
            onDismissRequest = { candidateToPromote = null },
            title = { Text("转为正式线索？") },
            text = { Text("${lead.displayNameSnapshot} 将进入正式线索列表，之后可参与推进判断。") },
            dismissButton = { TextButton(onClick = { candidateToPromote = null }) { Text("取消") } },
            confirmButton = {
                TextButton(onClick = {
                    candidateToPromote = null
                    viewModel.promoteCandidateLead(lead.leadId)
                }) { Text("确认转正", color = ZhiBanTerracotta) }
            },
        )
    }

    leadToConvert?.let { lead ->
        CrmConvertLeadDialog(
            lead = lead,
            onDismiss = { leadToConvert = null },
            onConfirm = { input ->
                val converting = lead
                leadToConvert = null
                viewModel.convertLeadToOpportunity(converting.leadId, input, onConverted = onOpenOpportunity)
            },
        )
    }
}

@Composable
internal fun CrmLeadPoolCard(lead: CrmLeadEntity, onQualify: () -> Unit, onConvert: () -> Unit, onDisqualify: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().testTag("crm-lead-${lead.leadId}").zhiBanCardSurface().padding(ZhiBanSpacing.Lg),
        verticalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    lead.displayNameSnapshot,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    leadDetailLine(lead),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                crmLeadStatusLabel(lead.status),
                style = MaterialTheme.typography.labelMedium,
                color = ZhiBanTerracotta,
            )
        }
        Text(
            "知伴判断 ${(lead.confidence * 100).toInt()}% · ${crmLeadSourceLabel(lead.sourceType)} · ${formatCrmDate(lead.updatedAtEpochMs)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(
                onClick = onDisqualify,
                modifier = Modifier.testTag("crm-lead-disqualify-${lead.leadId}").defaultMinSize(minHeight = ZhiBanSize.TouchTarget),
            ) { Text("放弃") }
            if (lead.status != CrmLeadStatus.QUALIFIED) {
                TextButton(
                    onClick = onQualify,
                    modifier = Modifier.testTag("crm-lead-qualify-${lead.leadId}").defaultMinSize(minHeight = ZhiBanSize.TouchTarget),
                ) { Text("标记需求") }
            }
            TextButton(
                onClick = onConvert,
                modifier = Modifier.testTag("crm-lead-convert-${lead.leadId}").defaultMinSize(minHeight = ZhiBanSize.TouchTarget),
            ) { Text("转化为商机", color = ZhiBanTerracotta) }
        }
    }
}

@Composable
internal fun CrmConvertLeadDialog(lead: CrmLeadEntity, onDismiss: () -> Unit, onConfirm: (CrmLeadConversionInput) -> Unit) {
    var title by remember(lead.leadId) { mutableStateOf(defaultOpportunityTitle(lead)) }
    var account by remember(lead.leadId) { mutableStateOf(lead.companyNameSnapshot ?: lead.displayNameSnapshot) }
    var amountText by remember(lead.leadId) { mutableStateOf("") }
    var closeDateText by remember(lead.leadId) { mutableStateOf("") }
    val amountValid = amountText.isBlank() || amountText.toDoubleOrNull()?.let { it >= 0 } == true
    val closeDateValid = closeDateText.isBlank() || runCatching { LocalDate.parse(closeDateText) }.isSuccess
    val canConfirm = title.isNotBlank() && account.isNotBlank() && amountValid && closeDateValid

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("转化为商机") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Md)) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("商机标题") }, singleLine = true)
                OutlinedTextField(value = account, onValueChange = { account = it }, label = { Text("客户名") }, singleLine = true)
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text("预计金额（元，可留空）") },
                    singleLine = true,
                    isError = !amountValid,
                )
                OutlinedTextField(
                    value = closeDateText,
                    onValueChange = { closeDateText = it },
                    label = { Text("预计成交日期 YYYY-MM-DD（可留空）") },
                    singleLine = true,
                    isError = !closeDateValid,
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        confirmButton = {
            TextButton(
                onClick = {
                    if (canConfirm) onConfirm(buildConversionInput(title, account, amountText, closeDateText))
                },
                enabled = canConfirm,
                modifier = Modifier.testTag("crm-convert-confirm"),
            ) { Text("创建商机", color = ZhiBanTerracotta) }
        },
    )
}

private val FORMAL_LEAD_STATUSES = setOf(CrmLeadStatus.NEW, CrmLeadStatus.CONTACTED, CrmLeadStatus.QUALIFIED)

private fun leadDetailLine(lead: CrmLeadEntity): String = listOfNotNull(lead.companyNameSnapshot, lead.fitSummary).joinToString(" · ").ifBlank { "等待下一步推进" }

private fun defaultOpportunityTitle(lead: CrmLeadEntity): String = lead.companyNameSnapshot?.let { "$it 合作" } ?: "${lead.displayNameSnapshot} 的商机"

internal fun crmLeadStatusLabel(status: String): String = when (status) {
    CrmLeadStatus.NEW -> "线索"
    CrmLeadStatus.CONTACTED -> "已联系"
    CrmLeadStatus.QUALIFIED -> "已确认需求"
    CrmLeadStatus.CONVERTED -> "已转化"
    CrmLeadStatus.DISQUALIFIED -> "已放弃"
    else -> "线索"
}

internal fun crmLeadSourceLabel(sourceType: String): String = when (sourceType) {
    "AGENT_AUTO" -> "知伴发现"
    "USER_CONFIRMED" -> "你确认"
    "USER" -> "手动添加"
    else -> "其他来源"
}

private fun buildConversionInput(title: String, account: String, amountText: String, closeDateText: String): CrmLeadConversionInput {
    val valueMinor = amountText.toDoubleOrNull()?.let { (it * 100).toLong() }
    val closeAt = closeDateText.takeIf { it.isNotBlank() }?.let {
        runCatching { LocalDate.parse(it).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()
    }
    return CrmLeadConversionInput(
        title = title.trim(),
        accountName = account.trim(),
        valueMinor = valueMinor,
        expectedCloseAtEpochMs = closeAt,
    )
}
