package com.zhiban.rebuild.ui.tabs

import com.zhiban.rebuild.data.crm.CrmActionStatus
import com.zhiban.rebuild.data.crm.CrmOpportunityStage
import com.zhiban.rebuild.data.crm.CrmRecordStatus
import com.zhiban.rebuild.data.crm.CrmSuggestionStatus

internal enum class CrmPriorityKind {
    OVERDUE,
    DUE_TODAY,
    AGENT_SUGGESTION,
    MISSING_NEXT_ACTION,
    CANDIDATE_LEAD,
}

/** A deterministic, evidence-backed item for the CRM workbench. No predictive score is invented. */
internal data class CrmPriorityUi(
    val kind: CrmPriorityKind,
    val title: String,
    val context: String,
    val reason: String,
    val opportunityId: String? = null,
    val dueAtEpochMs: Long? = null,
    val suggestionId: String? = null,
    val candidateLeadId: String? = null,
)

internal fun buildCrmPriorities(state: CrmWorkbenchUiState): List<CrmPriorityUi> {
    val priorities = mutableListOf<CrmPriorityUi>()
    priorities += state.followUps.overdue.sortedBy { it.entity.dueAtEpochMs }.map { action ->
        CrmPriorityUi(
            kind = CrmPriorityKind.OVERDUE,
            title = action.entity.title,
            context = "${action.contactName} · ${action.opportunityTitle}",
            reason = "已逾期 ${formatCrmDateTime(action.entity.dueAtEpochMs)}",
            opportunityId = action.entity.opportunityId,
            dueAtEpochMs = action.entity.dueAtEpochMs,
        )
    }
    priorities += state.followUps.dueToday.sortedBy { it.entity.dueAtEpochMs }.map { action ->
        CrmPriorityUi(
            kind = CrmPriorityKind.DUE_TODAY,
            title = action.entity.title,
            context = "${action.contactName} · ${action.opportunityTitle}",
            reason = "今天 ${formatCrmDateTime(action.entity.dueAtEpochMs)}",
            opportunityId = action.entity.opportunityId,
            dueAtEpochMs = action.entity.dueAtEpochMs,
        )
    }
    priorities += state.suggestions.map { suggestion ->
        CrmPriorityUi(
            kind = CrmPriorityKind.AGENT_SUGGESTION,
            title = suggestion.entity.title,
            context = listOfNotNull(suggestion.contactName, suggestion.opportunityTitle).joinToString(" · "),
            reason = suggestion.entity.rationale,
            opportunityId = suggestion.entity.opportunityId,
            suggestionId = suggestion.entity.suggestionId,
        )
    }
    val opportunitiesWithActions = state.actions.mapTo(mutableSetOf()) { it.entity.opportunityId }
    priorities += state.opportunities.filter {
        it.entity.status == CrmRecordStatus.OPEN && it.entity.opportunityId !in opportunitiesWithActions
    }.map { opportunity ->
        CrmPriorityUi(
            kind = CrmPriorityKind.MISSING_NEXT_ACTION,
            title = "安排下一步：${opportunity.entity.title}",
            context = "${opportunity.contactName} · ${opportunity.entity.accountNameSnapshot}",
            reason = "当前没有待办，推进容易中断",
            opportunityId = opportunity.entity.opportunityId,
        )
    }
    priorities += state.candidateLeads.map { lead ->
        CrmPriorityUi(
            kind = CrmPriorityKind.CANDIDATE_LEAD,
            title = "判断是否跟进 ${lead.displayNameSnapshot}",
            context = lead.companyNameSnapshot ?: "新候选线索",
            reason = lead.fitSummary ?: "知伴发现了新的沟通信号",
            candidateLeadId = lead.leadId,
        )
    }
    return priorities
}

internal data class CrmOpportunityGuidanceUi(val title: String, val summary: String, val evidence: String, val dueAtEpochMs: Long? = null)

/** Chooses one next-best action from verified CRM fields, tasks, suggestions, and relationship links. */
internal fun buildCrmOpportunityGuidance(state: CrmOpportunityDetailUiState, nowEpochMs: Long): CrmOpportunityGuidanceUi? {
    val opportunity = state.opportunity ?: return null
    if (opportunity.entity.status != CrmRecordStatus.OPEN) return null
    val pendingActions = state.actions.filter { it.entity.status == CrmActionStatus.PENDING }
    val overdue = pendingActions.filter { it.entity.dueAtEpochMs != null && it.entity.dueAtEpochMs < nowEpochMs }
        .minByOrNull { it.entity.dueAtEpochMs ?: Long.MAX_VALUE }
    if (overdue != null) {
        return CrmOpportunityGuidanceUi(
            title = "先完成逾期跟进",
            summary = overdue.entity.title,
            evidence = "原定 ${formatCrmDateTime(overdue.entity.dueAtEpochMs)}，目前仍未完成",
            dueAtEpochMs = overdue.entity.dueAtEpochMs,
        )
    }
    state.suggestions.firstOrNull { it.entity.status == CrmSuggestionStatus.PENDING }?.let { suggestion ->
        return CrmOpportunityGuidanceUi(
            title = suggestion.entity.title,
            summary = suggestion.entity.summary,
            evidence = suggestion.entity.rationale,
        )
    }
    if (pendingActions.isEmpty()) {
        return CrmOpportunityGuidanceUi(
            title = "确定下一步动作",
            summary = "让知伴结合最近沟通，准备一个明确的跟进动作",
            evidence = "当前机会没有待办",
        )
    }
    if (opportunity.entity.needSummary.isNullOrBlank()) {
        return CrmOpportunityGuidanceUi(
            title = "补齐客户需求",
            summary = "梳理对方要解决的问题、时间和判断标准",
            evidence = "机会中还没有已确认的需求摘要",
        )
    }
    val stageIndex = CrmOpportunityStage.activeStages.indexOf(opportunity.entity.stage)
    if (stageIndex >= CrmOpportunityStage.activeStages.indexOf(CrmOpportunityStage.QUALIFIED) && state.stakeholders.isEmpty()) {
        return CrmOpportunityGuidanceUi(
            title = "确认关键关系人",
            summary = "确认谁决策、谁推动、谁实际使用",
            evidence = "机会已进入${crmStageLabel(opportunity.entity.stage)}，但还没有关键关系人",
        )
    }
    val nextAction = pendingActions.minByOrNull { it.entity.dueAtEpochMs ?: Long.MAX_VALUE } ?: return null
    return CrmOpportunityGuidanceUi(
        title = "准备下一次沟通",
        summary = nextAction.entity.title,
        evidence = nextAction.entity.rationale ?: "依据已确认的下一步动作",
        dueAtEpochMs = nextAction.entity.dueAtEpochMs,
    )
}

internal fun crmOpportunityStatusLine(opportunity: CrmOpportunityUi, actions: List<CrmActionUi>, nowEpochMs: Long): String {
    val matching = actions.filter { it.entity.opportunityId == opportunity.entity.opportunityId && it.entity.status == CrmActionStatus.PENDING }
    val next = matching.minByOrNull { it.entity.dueAtEpochMs ?: Long.MAX_VALUE } ?: return "尚未安排下一步"
    val dueAt = next.entity.dueAtEpochMs
    return when {
        dueAt == null -> "下一步 · ${next.entity.title}"
        dueAt < nowEpochMs -> "已逾期 · ${next.entity.title}"
        else -> "下一步 ${formatCrmDateTime(dueAt)} · ${next.entity.title}"
    }
}
