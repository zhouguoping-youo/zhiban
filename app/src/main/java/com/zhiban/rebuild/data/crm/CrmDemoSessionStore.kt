package com.zhiban.rebuild.data.crm

import com.zhiban.rebuild.data.contact.ContactEntity
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Process-local, read-only-by-default CRM demonstration space.
 *
 * Demo rows never enter Room, the contact store, or the calendar store. Mutations made while
 * exploring the demo only update this in-memory snapshot and disappear when the user exits.
 */
@Singleton
class CrmDemoSessionStore @Inject constructor() {
    private val mutableDataset = MutableStateFlow<CrmDemoDataset?>(null)
    val dataset: StateFlow<CrmDemoDataset?> = mutableDataset.asStateFlow()

    fun enter(nowEpochMs: Long = System.currentTimeMillis()) {
        if (mutableDataset.value == null) mutableDataset.value = createCrmDemoDataset(nowEpochMs)
    }

    fun exit() {
        mutableDataset.value = null
    }

    fun setActionCompleted(actionId: String, completed: Boolean, nowEpochMs: Long = System.currentTimeMillis()): Boolean {
        var changed = false
        mutableDataset.update { current ->
            current?.copy(
                actions = current.actions.map { action ->
                    if (action.actionId != actionId) {
                        action
                    } else {
                        changed = true
                        action.copy(
                            status = if (completed) CrmActionStatus.COMPLETED else CrmActionStatus.PENDING,
                            updatedAtEpochMs = nowEpochMs,
                        )
                    }
                },
            )
        }
        return changed
    }

    fun setSuggestionStatus(suggestionId: String, accepted: Boolean, nowEpochMs: Long = System.currentTimeMillis()): Boolean {
        var changed = false
        mutableDataset.update { current ->
            current?.copy(
                suggestions = current.suggestions.map { suggestion ->
                    if (suggestion.suggestionId != suggestionId) {
                        suggestion
                    } else {
                        changed = true
                        suggestion.copy(
                            status = if (accepted) CrmSuggestionStatus.ACCEPTED else CrmSuggestionStatus.DISMISSED,
                            updatedAtEpochMs = nowEpochMs,
                        )
                    }
                },
            )
        }
        return changed
    }

    fun changeStage(opportunityId: String, stage: String, nowEpochMs: Long = System.currentTimeMillis()): Boolean {
        var changed = false
        mutableDataset.update { current ->
            val previous = current?.opportunities?.firstOrNull { it.opportunityId == opportunityId }
                ?: return@update current
            CrmOpportunityStage.requireTransitionAllowed(previous.stage, stage)
            if (previous.stage == stage) return@update current
            changed = true
            val recordStatus = when (stage) {
                CrmOpportunityStage.WON -> CrmRecordStatus.WON
                CrmOpportunityStage.LOST -> CrmRecordStatus.LOST
                else -> CrmRecordStatus.OPEN
            }
            current.copy(
                opportunities = current.opportunities.map {
                    if (it.opportunityId == opportunityId) {
                        it.copy(
                            stage = stage,
                            status = recordStatus,
                            probabilityPercent = CrmOpportunityStage.probabilityPercent(stage),
                            updatedAtEpochMs = nowEpochMs,
                        )
                    } else {
                        it
                    }
                },
                stageHistory = listOf(
                    CrmStageHistoryEntity(
                        historyId = "crm-demo-history-session-$nowEpochMs",
                        opportunityId = opportunityId,
                        fromStage = previous.stage,
                        toStage = stage,
                        reason = "演示空间内调整",
                        sourceType = DEMO_SOURCE,
                        userConfirmed = true,
                        changedAtEpochMs = nowEpochMs,
                    ),
                ) + current.stageHistory,
            )
        }
        return changed
    }

    companion object {
        const val DEMO_SOURCE = "DEMO"
    }
}

data class CrmDemoDataset(
    val contacts: List<ContactEntity>,
    val leads: List<CrmLeadEntity>,
    val opportunities: List<CrmOpportunityEntity>,
    val stakeholders: List<CrmOpportunityStakeholderEntity>,
    val activities: List<CrmActivityEntity>,
    val actions: List<CrmNextActionEntity>,
    val suggestions: List<CrmAgentSuggestionEntity>,
    val stageHistory: List<CrmStageHistoryEntity>,
)

internal fun createCrmDemoDataset(nowEpochMs: Long): CrmDemoDataset {
    fun contact(id: String, name: String, company: String, title: String) = ContactEntity(
        contactId = id,
        displayName = name,
        normalizedName = name.lowercase(),
        phone = null,
        email = null,
        wechatId = null,
        company = company,
        title = title,
        aliasesJson = "[]",
        tagsJson = "[\"CRM演示\"]",
        note = "仅存在于当前演示会话",
        avatarUri = null,
        source = "CRM_DEMO_MEMORY",
        deletedAtEpochMs = null,
        createdAtEpochMs = nowEpochMs,
        updatedAtEpochMs = nowEpochMs,
    )

    val wang = contact("crm-demo-contact-wang", "王建国", "华辰制造有限公司", "信息化负责人")
    val liu = contact("crm-demo-contact-liu", "刘志强", "远峰数据科技有限公司", "采购总监")
    val li = contact("crm-demo-contact-li", "李思琪", "云途零售集团", "运营副总裁")
    val contacts = listOf(wang, liu, li)
    val zoneId = ZoneId.systemDefault()
    val startOfToday = Instant.ofEpochMilli(nowEpochMs).atZone(zoneId).toLocalDate()
        .atStartOfDay(zoneId).toInstant().toEpochMilli()
    fun at(days: Long, hour: Int): Long = startOfToday + days * 86_400_000L + hour * 3_600_000L

    val core = buildDemoCore(wang, liu, li, ::at, nowEpochMs)
    val tail = buildDemoTail(wang, liu, li, core.opportunities, ::at, nowEpochMs)
    return CrmDemoDataset(contacts, core.leads, core.opportunities, core.stakeholders, core.activities, tail.actions, tail.suggestions, tail.history)
}

private data class DemoCoreSecondHalf(val stakeholders: List<CrmOpportunityStakeholderEntity>, val activities: List<CrmActivityEntity>)

private data class DemoCore(
    val leads: List<CrmLeadEntity>,
    val opportunities: List<CrmOpportunityEntity>,
    val stakeholders: List<CrmOpportunityStakeholderEntity>,
    val activities: List<CrmActivityEntity>,
)

private data class DemoCoreTail(
    val actions: List<CrmNextActionEntity>,
    val suggestions: List<CrmAgentSuggestionEntity>,
    val history: List<CrmStageHistoryEntity>,
)

private fun buildDemoCore(wang: ContactEntity, liu: ContactEntity, li: ContactEntity, at: (Long, Int) -> Long, nowEpochMs: Long): DemoCore {
    val leads = listOf(
        CrmLeadEntity(
            "crm-demo-lead-wang", wang.contactId, wang.displayName, wang.company, CrmLeadStatus.CONVERTED,
            "DEMO", "demo:message", "有明确的私有化部署需求，时间计划清晰", 0.94, true,
            nowEpochMs - 12 * 86_400_000L, nowEpochMs,
        ),
        CrmLeadEntity(
            "crm-demo-lead-liu", liu.contactId, liu.displayName, liu.company, CrmLeadStatus.CONVERTED, "DEMO", "demo:meeting", "已确认业务需求，预算和决策链仍待补充", 0.86, true,
            nowEpochMs - 8 * 86_400_000L, nowEpochMs,
        ),
        CrmLeadEntity(
            "crm-demo-lead-li", li.contactId, li.displayName, li.company, CrmLeadStatus.CONVERTED, "DEMO", "demo:share", "续约窗口临近，客户提出合同条款调整", 0.91, true,
            nowEpochMs - 20 * 86_400_000L, nowEpochMs,
        ),
    )
    val opportunities = listOf(
        CrmOpportunityEntity(
            "crm-demo-opp-private", "私有化部署项目", wang.company!!, wang.contactId, leads[0].leadId,
            CrmOpportunityStage.PROPOSAL, CrmRecordStatus.OPEN, 68_000_000, "CNY", 65,
            at(
                18,
                18,
            ),
            "知伴企业版私有化部署", "需要完成安全评审，并在本月确认交付范围", null, "DEMO",
            nowEpochMs - 11 * 86_400_000L, nowEpochMs,
        ),
        CrmOpportunityEntity(
            "crm-demo-opp-data", "数据平台升级", liu.company!!, liu.contactId, leads[1].leadId,
            CrmOpportunityStage.QUALIFIED, CrmRecordStatus.OPEN, 42_000_000, "CNY", 45,
            at(
                32,
                18,
            ),
            "数据治理与智能分析方案", "技术需求已确认，预算范围和最终决策人待核实", null, "DEMO",
            nowEpochMs - 7 * 86_400_000L, nowEpochMs,
        ),
        CrmOpportunityEntity(
            "crm-demo-opp-renewal", "年度服务续约", li.company!!, li.contactId, leads[2].leadId,
            CrmOpportunityStage.NEGOTIATION, CrmRecordStatus.OPEN, 18_000_000, "CNY", 80,
            at(
                9,
                18,
            ),
            "年度服务与运营支持", "续约意向明确，正在确认服务范围和付款节点", null, "DEMO",
            nowEpochMs - 19 * 86_400_000L, nowEpochMs,
        ),
    )
    val opportunitiesHalf = buildDemoCoreSecondHalf(wang, liu, li, opportunities, at, nowEpochMs)
    return DemoCore(leads, opportunities, opportunitiesHalf.stakeholders, opportunitiesHalf.activities)
}

private fun buildDemoCoreSecondHalf(
    wang: ContactEntity,
    liu: ContactEntity,
    li: ContactEntity,
    opportunities: List<CrmOpportunityEntity>,
    at: (Long, Int) -> Long,
    nowEpochMs: Long,
): DemoCoreSecondHalf {
    val stakeholders = listOf(
        CrmOpportunityStakeholderEntity(
            opportunities[0].opportunityId,
            wang.contactId,
            "DECISION_MAKER",
            "HIGH",
            true,
            nowEpochMs,
        ),
        CrmOpportunityStakeholderEntity(
            opportunities[1].opportunityId,
            liu.contactId,
            "CHAMPION",
            "HIGH",
            true,
            nowEpochMs,
        ),
        CrmOpportunityStakeholderEntity(
            opportunities[2].opportunityId,
            li.contactId,
            "DECISION_MAKER",
            "HIGH",
            true,
            nowEpochMs,
        ),
    )
    val activities = listOf(
        CrmActivityEntity(
            "crm-demo-act-1", opportunities[0].opportunityId, wang.contactId, "MEETING", "完成方案评审", "客户确认总体方案可行，要求补充交付计划和安全边界。",
            nowEpochMs - 86_400_000L, "DEMO", "demo:meeting:1", "会议纪要中出现本周给交付计划", true, nowEpochMs,
        ),
        CrmActivityEntity(
            "crm-demo-act-2", opportunities[0].opportunityId, wang.contactId, "MESSAGE", "确认评审反馈", "王建国确认安全团队将在周五前反馈。",
            nowEpochMs - 2 * 86_400_000L, "DEMO", "demo:message:1", "演示消息摘要", true, nowEpochMs,
        ),
        CrmActivityEntity(
            "crm-demo-act-3", opportunities[1].opportunityId, liu.contactId, "CALL", "需求访谈", "已确认数据治理范围，但预算区间和决策链仍不完整。",
            nowEpochMs - 3 * 86_400_000L, "DEMO", "demo:call:1", "演示通话摘要", true, nowEpochMs,
        ),
        CrmActivityEntity(
            "crm-demo-act-4", opportunities[2].opportunityId, li.contactId, "EMAIL", "收到续约条款意见", "客户希望调整服务范围并分两期付款。",
            nowEpochMs - 86_400_000L, "DEMO", "demo:email:1", "演示邮件摘要", true, nowEpochMs,
        ),
    )
    return DemoCoreSecondHalf(stakeholders, activities)
}

private fun buildDemoTail(
    wang: ContactEntity,
    liu: ContactEntity,
    li: ContactEntity,
    opportunities: List<CrmOpportunityEntity>,
    at: (Long, Int) -> Long,
    nowEpochMs: Long,
): DemoCoreTail {
    val actions = listOf(
        CrmNextActionEntity(
            "crm-demo-action-private", opportunities[0].opportunityId, wang.contactId, "SEND_MATERIAL", "给王建国发送交付计划",
            at(
                0,
                18,
            ),
            CrmActionStatus.PENDING, 100, "客户明确要求本周确认交付周期", "DEMO", null, nowEpochMs, nowEpochMs,
        ),
        CrmNextActionEntity(
            "crm-demo-action-data", opportunities[1].opportunityId, liu.contactId, "CALL", "与刘志强确认预算和决策链",
            at(
                2,
                15,
            ),
            CrmActionStatus.PENDING, 80, "需求已确认，但预算和最终决策人仍为空", "DEMO", null, nowEpochMs, nowEpochMs,
        ),
        CrmNextActionEntity(
            "crm-demo-action-renewal", opportunities[2].opportunityId, li.contactId, "REVIEW", "复核李思琪的续约合同条款",
            at(
                3,
                11,
            ),
            CrmActionStatus.PENDING, 90, "预计成交日期临近，合同条款仍有两项分歧", "DEMO", null, nowEpochMs, nowEpochMs,
        ),
    )
    val suggestions = listOf(
        CrmAgentSuggestionEntity(
            "crm-demo-suggestion-data", opportunities[1].opportunityId, liu.contactId, "QUALIFICATION_GAP", "先确认预算和最终决策人",
            "在继续准备完整方案前，建议先补齐预算范围和决策链。", "最近一次需求访谈确认了技术范围，但没有预算和最终决策人信息。",
            "[\"demo:call:1\"]", 0.89, "{\"action\":\"ASK_AGENT\"}", CrmSuggestionStatus.PENDING, nowEpochMs, nowEpochMs,
        ),
        CrmAgentSuggestionEntity(
            "crm-demo-suggestion-renewal", opportunities[2].opportunityId, li.contactId, "DEAL_RISK", "续约条款需要本周收敛",
            "建议准备两套付款节点方案，并明确不可调整的服务边界。", "预计成交日期还有 9 天，客户仍要求调整服务范围和付款节点。",
            "[\"demo:email:1\"]", 0.84, "{\"action\":\"ASK_AGENT\"}", CrmSuggestionStatus.PENDING, nowEpochMs, nowEpochMs,
        ),
    )
    val history = opportunities.mapIndexed { index, opportunity ->
        CrmStageHistoryEntity(
            "crm-demo-history-${index + 1}",
            opportunity.opportunityId,
            null,
            opportunity.stage,
            "根据已确认的演示沟通建立",
            "DEMO",
            true,
            opportunity.createdAtEpochMs,
        )
    }
    return DemoCoreTail(actions, suggestions, history)
}
