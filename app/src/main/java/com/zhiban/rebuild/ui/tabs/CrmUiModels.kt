package com.zhiban.rebuild.ui.tabs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhiban.rebuild.data.agent.AgentDataRepository
import com.zhiban.rebuild.data.agent.CrmLeadConversionInput
import com.zhiban.rebuild.data.contact.ContactEntity
import com.zhiban.rebuild.data.crm.CrmActivityEntity
import com.zhiban.rebuild.data.crm.CrmAgentSuggestionEntity
import com.zhiban.rebuild.data.crm.CrmDemoDataset
import com.zhiban.rebuild.data.crm.CrmDemoSessionStore
import com.zhiban.rebuild.data.crm.CrmLeadEntity
import com.zhiban.rebuild.data.crm.CrmLeadStatus
import com.zhiban.rebuild.data.crm.CrmNextActionEntity
import com.zhiban.rebuild.data.crm.CrmOpportunityEntity
import com.zhiban.rebuild.data.crm.CrmOpportunityStage
import com.zhiban.rebuild.data.crm.CrmOpportunityStakeholderEntity
import com.zhiban.rebuild.data.crm.CrmRecordStatus
import com.zhiban.rebuild.data.crm.CrmStageHistoryEntity
import com.zhiban.rebuild.runtime.governance.AutoWriteRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal data class CrmOpportunityUi(val entity: CrmOpportunityEntity, val contactName: String)

internal data class CrmActionUi(val entity: CrmNextActionEntity, val opportunityTitle: String, val accountName: String, val contactName: String)

internal data class CrmSuggestionUi(val entity: CrmAgentSuggestionEntity, val opportunityTitle: String?, val accountName: String?, val contactName: String?)

internal data class CrmStakeholderUi(val entity: CrmOpportunityStakeholderEntity, val contactName: String, val company: String?, val title: String?)

/** Follow-up reminder buckets for the pending next actions, split by local day boundaries. */
internal data class CrmFollowUpGroups(
    val overdue: List<CrmActionUi> = emptyList(),
    val dueToday: List<CrmActionUi> = emptyList(),
    val upcoming: List<CrmActionUi> = emptyList(),
    val unscheduled: List<CrmActionUi> = emptyList(),
) {
    val needsAttentionCount: Int get() = overdue.size + dueToday.size
}

/** Groups pending actions into overdue / due-today / upcoming / unscheduled by their due time. */
internal fun buildCrmFollowUpGroups(actions: List<CrmActionUi>, todayStartEpochMs: Long, tomorrowStartEpochMs: Long): CrmFollowUpGroups {
    val overdue = mutableListOf<CrmActionUi>()
    val dueToday = mutableListOf<CrmActionUi>()
    val upcoming = mutableListOf<CrmActionUi>()
    val unscheduled = mutableListOf<CrmActionUi>()
    actions.forEach { action ->
        val dueAt = action.entity.dueAtEpochMs
        when {
            dueAt == null -> unscheduled += action
            dueAt < todayStartEpochMs -> overdue += action
            dueAt < tomorrowStartEpochMs -> dueToday += action
            else -> upcoming += action
        }
    }
    return CrmFollowUpGroups(overdue = overdue, dueToday = dueToday, upcoming = upcoming, unscheduled = unscheduled)
}

/** Dashboard counters rendered at the top of the CRM home page. Amounts are in minor units (分). */
internal data class CrmDashboardUi(
    val openOpportunityValueMinor: Long = 0,
    val wonOpportunityCount: Int = 0,
    val lostOpportunityCount: Int = 0,
    val newLeadsCount: Int = 0,
    val activitiesCount: Int = 0,
    val overdueActionCount: Int = 0,
    val dueTodayActionCount: Int = 0,
) {
    val isEmpty: Boolean get() =
        openOpportunityValueMinor == 0L && wonOpportunityCount == 0 && lostOpportunityCount == 0 &&
            newLeadsCount == 0 && activitiesCount == 0 && overdueActionCount == 0 && dueTodayActionCount == 0
}

/** Builds the dashboard summary from real CRM data; counts only, never inferred. */
internal fun buildCrmDashboardUi(
    opportunities: List<CrmOpportunityUi>,
    followUps: CrmFollowUpGroups,
    newLeadsCount: Int,
    activitiesCount: Int,
): CrmDashboardUi = CrmDashboardUi(
    openOpportunityValueMinor = opportunities.filter { it.entity.status == CrmRecordStatus.OPEN }.mapNotNull { it.entity.valueMinor }.sum(),
    wonOpportunityCount = opportunities.count { it.entity.stage == CrmOpportunityStage.WON },
    lostOpportunityCount = opportunities.count { it.entity.stage == CrmOpportunityStage.LOST },
    newLeadsCount = newLeadsCount,
    activitiesCount = activitiesCount,
    overdueActionCount = followUps.overdue.size,
    dueTodayActionCount = followUps.dueToday.size,
)

internal data class CrmWorkbenchUiState(
    val leads: List<CrmLeadEntity> = emptyList(),
    val candidateLeads: List<CrmLeadEntity> = emptyList(),
    val opportunities: List<CrmOpportunityUi> = emptyList(),
    val actions: List<CrmActionUi> = emptyList(),
    val suggestions: List<CrmSuggestionUi> = emptyList(),
    val followUps: CrmFollowUpGroups = CrmFollowUpGroups(),
    val dashboard: CrmDashboardUi = CrmDashboardUi(),
    val isDemo: Boolean = false,
    val demoNotice: String? = null,
)

internal data class CrmOpportunityDetailUiState(
    val opportunity: CrmOpportunityUi? = null,
    val activities: List<CrmActivityEntity> = emptyList(),
    val actions: List<CrmActionUi> = emptyList(),
    val suggestions: List<CrmSuggestionUi> = emptyList(),
    val stakeholders: List<CrmStakeholderUi> = emptyList(),
    val stageHistory: List<CrmStageHistoryEntity> = emptyList(),
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
)

private data class CrmDetailCore(
    val opportunity: CrmOpportunityEntity?,
    val activities: List<CrmActivityEntity>,
    val actions: List<CrmNextActionEntity>,
    val suggestions: List<CrmAgentSuggestionEntity>,
)

private data class CrmDetailRelations(
    val stakeholders: List<CrmOpportunityStakeholderEntity>,
    val history: List<CrmStageHistoryEntity>,
    val contacts: List<ContactEntity>,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CrmCapabilityViewModel @Inject constructor(
    private val repository: AgentDataRepository,
    private val demoStore: CrmDemoSessionStore,
    private val autoWriteRepository: AutoWriteRepository,
) : ViewModel() {
    private val contacts = repository.observeContacts()
    private val selectedOpportunityId = MutableStateFlow<String?>(null)
    private val demoNotice = MutableStateFlow<String?>(null)
    private val zone: ZoneId = ZoneId.systemDefault()

    private val realCore = combine(
        repository.observeCrmLeads(),
        repository.observeCrmOpportunities(),
        repository.observeCrmPendingActions(),
        repository.observeCrmPendingSuggestions(),
        contacts,
        repository.observeCrmDashboardCounts(System.currentTimeMillis() - DASHBOARD_WINDOW_MS),
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val leads = values[0] as List<CrmLeadEntity>

        @Suppress("UNCHECKED_CAST")
        val opportunities = values[1] as List<CrmOpportunityEntity>

        @Suppress("UNCHECKED_CAST")
        val actions = values[2] as List<CrmNextActionEntity>

        @Suppress("UNCHECKED_CAST")
        val suggestions = values[3] as List<CrmAgentSuggestionEntity>

        @Suppress("UNCHECKED_CAST")
        val contactRows = values[4] as List<ContactEntity>

        @Suppress("UNCHECKED_CAST")
        val dashboardCounts = values[5] as Pair<Int, Int>
        val contactMap = contactRows.associateBy(ContactEntity::contactId)
        val opportunityMap = opportunities.associateBy(CrmOpportunityEntity::opportunityId)
        val actionUis = actions.mapNotNull { it.toUi(opportunityMap, contactMap) }
        val now = System.currentTimeMillis()
        val todayStart = LocalDate.now().atStartOfDay(zone).toInstant().toEpochMilli()
        val tomorrowStart = LocalDate.now().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val followUps = buildCrmFollowUpGroups(actionUis, todayStart, tomorrowStart)
        val opportunityUis = opportunities.map { it.toUi(contactMap) }
        CrmWorkbenchUiState(
            leads = leads,
            opportunities = opportunityUis,
            actions = actionUis,
            suggestions = suggestions.map { it.toUi(opportunityMap, contactMap) },
            followUps = followUps,
            dashboard = buildCrmDashboardUi(
                opportunities = opportunityUis,
                followUps = followUps,
                newLeadsCount = dashboardCounts.first,
                activitiesCount = dashboardCounts.second,
            ),
            isDemo = false,
        )
    }

    private val realState = combine(realCore, repository.observeCrmCandidateLeads()) { core, candidates ->
        core.copy(candidateLeads = candidates)
    }

    internal val state: StateFlow<CrmWorkbenchUiState> = combine(
        realState,
        demoStore.dataset,
        demoNotice,
    ) { real, demo, notice ->
        (demo?.toWorkbenchState() ?: real).copy(demoNotice = notice)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CrmWorkbenchUiState())

    internal val detailState: StateFlow<CrmOpportunityDetailUiState> = selectedOpportunityId
        .flatMapLatest { opportunityId ->
            if (opportunityId == null) {
                flowOf(CrmOpportunityDetailUiState())
            } else {
                combine(
                    repository.observeCrmOpportunity(opportunityId),
                    repository.observeCrmActivities(opportunityId),
                    repository.observeCrmActions(opportunityId),
                    repository.observeCrmSuggestions(opportunityId),
                ) { opportunity, activities, actions, suggestions ->
                    CrmDetailCore(opportunity, activities, actions, suggestions)
                }.combine(
                    combine(
                        repository.observeCrmStakeholders(opportunityId),
                        repository.observeCrmStageHistory(opportunityId),
                        contacts,
                    ) { stakeholders, history, contactRows ->
                        CrmDetailRelations(stakeholders, history, contactRows)
                    },
                ) { core, relations ->
                    val contactMap = relations.contacts.associateBy(ContactEntity::contactId)
                    val opportunity = core.opportunity
                    val opportunityMap = listOfNotNull(opportunity).associateBy(CrmOpportunityEntity::opportunityId)
                    CrmOpportunityDetailUiState(
                        opportunity = opportunity?.toUi(contactMap),
                        activities = core.activities,
                        actions = core.actions.mapNotNull { it.toUi(opportunityMap, contactMap) },
                        suggestions = core.suggestions.map { it.toUi(opportunityMap, contactMap) },
                        stakeholders = relations.stakeholders.map { stakeholder ->
                            val contact = contactMap[stakeholder.contactId]
                            CrmStakeholderUi(
                                stakeholder,
                                contact?.displayName ?: "联系人",
                                contact?.company,
                                contact?.title,
                            )
                        },
                        stageHistory = relations.history,
                        isLoading = false,
                    )
                }.combine(demoStore.dataset) { real, demo ->
                    demo?.toDetailState(opportunityId) ?: real
                }.catch {
                    emit(
                        CrmOpportunityDetailUiState(
                            isLoading = false,
                            errorMessage = "机会详情读取失败，请稍后重试",
                        ),
                    )
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CrmOpportunityDetailUiState())

    fun enterDemo() {
        demoNotice.value = null
        demoStore.enter()
    }

    fun exitDemo() {
        demoStore.exit()
        viewModelScope.launch {
            val summary = repository.clearLegacyCrmDemoData()
            demoNotice.value = if (summary.totalDeleted == 0) {
                "已退出演示；真实联系人和日程未改动"
            } else {
                "已退出演示，并清除 ${summary.totalDeleted} 条旧演示记录"
            }
        }
    }

    fun clearDemoNotice() {
        demoNotice.value = null
    }

    fun promoteCandidateLead(leadId: String) {
        viewModelScope.launch {
            if (!autoWriteRepository.promoteCandidateLead(leadId)) {
                demoNotice.value = "候选线索已变化，请刷新后重试"
            }
        }
    }

    fun ignoreCandidateLead(leadId: String) {
        viewModelScope.launch {
            if (!autoWriteRepository.ignoreCandidateLead(leadId)) {
                demoNotice.value = "候选线索已变化，请到自动整理中纠正"
            }
        }
    }

    fun selectOpportunity(opportunityId: String) {
        selectedOpportunityId.value = opportunityId
    }

    fun qualifyLead(leadId: String) {
        viewModelScope.launch {
            if (!repository.qualifyCrmLead(leadId)) {
                demoNotice.value = "该线索当前状态无法标记为已确认需求"
            }
        }
    }

    fun disqualifyLead(leadId: String) {
        viewModelScope.launch {
            if (!repository.disqualifyCrmLead(leadId)) {
                demoNotice.value = "该线索当前状态无法放弃"
            }
        }
    }

    fun convertLeadToOpportunity(leadId: String, input: CrmLeadConversionInput, onConverted: (String) -> Unit) {
        viewModelScope.launch {
            val opportunityId = repository.convertLeadToOpportunity(leadId, input)
            if (opportunityId == null) {
                demoNotice.value = "该线索当前状态无法转化为商机"
            } else {
                onConverted(opportunityId)
            }
        }
    }

    fun completeAction(actionId: String, completed: Boolean = true) {
        if (!demoStore.setActionCompleted(actionId, completed)) {
            viewModelScope.launch { repository.setCrmActionCompleted(actionId, completed) }
        }
    }

    fun dismissSuggestion(suggestionId: String) {
        if (!demoStore.setSuggestionStatus(suggestionId, accepted = false)) {
            viewModelScope.launch { repository.setCrmSuggestionStatus(suggestionId, accepted = false) }
        }
    }

    fun acceptCallFollowUpSuggestion(suggestionId: String) {
        viewModelScope.launch {
            if (!repository.acceptCallFollowUpSuggestion(suggestionId)) {
                demoNotice.value = "这条建议已处理或已失效"
            }
        }
    }

    fun acceptNewLeadSuggestion(suggestionId: String) {
        viewModelScope.launch {
            if (!repository.acceptNewLeadSuggestion(suggestionId)) {
                demoNotice.value = "这条建议已处理或已失效"
            }
        }
    }

    fun changeStage(opportunityId: String, stage: String) {
        if (!demoStore.changeStage(opportunityId, stage)) {
            viewModelScope.launch {
                repository.updateCrmOpportunityStage(opportunityId, stage, "用户在机会详情页确认调整")
            }
        }
    }

    private companion object {
        const val DASHBOARD_WINDOW_MS: Long = 7L * 24 * 60 * 60 * 1000
    }
}

private fun CrmDemoDataset.toWorkbenchState(): CrmWorkbenchUiState {
    val contactMap = contacts.associateBy(ContactEntity::contactId)
    val opportunityMap = opportunities.associateBy(CrmOpportunityEntity::opportunityId)
    val opportunityUis = opportunities.map { it.toUi(contactMap) }
    val actionUis = actions.filter { it.status == "PENDING" }.mapNotNull { it.toUi(opportunityMap, contactMap) }
    val zone = ZoneId.systemDefault()
    val now = System.currentTimeMillis()
    val todayStart = LocalDate.now().atStartOfDay(zone).toInstant().toEpochMilli()
    val tomorrowStart = LocalDate.now().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val followUps = buildCrmFollowUpGroups(actionUis, todayStart, tomorrowStart)
    val windowStart = now - 7L * 24 * 60 * 60 * 1000
    return CrmWorkbenchUiState(
        leads = leads,
        opportunities = opportunityUis,
        actions = actionUis,
        suggestions = suggestions.filter { it.status == "PENDING" }.map { it.toUi(opportunityMap, contactMap) },
        followUps = followUps,
        dashboard = buildCrmDashboardUi(
            opportunities = opportunityUis,
            followUps = followUps,
            newLeadsCount = leads.count { it.status != CrmLeadStatus.CANDIDATE && it.createdAtEpochMs >= windowStart },
            activitiesCount = activities.count { it.occurredAtEpochMs >= windowStart },
        ),
        isDemo = true,
    )
}

private fun CrmDemoDataset.toDetailState(opportunityId: String): CrmOpportunityDetailUiState? {
    val opportunity = opportunities.firstOrNull { it.opportunityId == opportunityId } ?: return null
    val contactMap = contacts.associateBy(ContactEntity::contactId)
    val opportunityMap = mapOf(opportunityId to opportunity)
    return CrmOpportunityDetailUiState(
        opportunity = opportunity.toUi(contactMap),
        activities = activities.filter {
            it.opportunityId == opportunityId
        }.sortedByDescending { it.occurredAtEpochMs },
        actions = actions.filter {
            it.opportunityId == opportunityId
        }.mapNotNull { it.toUi(opportunityMap, contactMap) },
        suggestions = suggestions.filter { it.opportunityId == opportunityId }.map { it.toUi(opportunityMap, contactMap) },
        stakeholders = stakeholders.filter { it.opportunityId == opportunityId }.map { stakeholder ->
            val contact = contactMap[stakeholder.contactId]
            CrmStakeholderUi(stakeholder, contact?.displayName ?: "联系人", contact?.company, contact?.title)
        },
        stageHistory = stageHistory.filter {
            it.opportunityId == opportunityId
        }.sortedByDescending { it.changedAtEpochMs },
        isLoading = false,
    )
}

private fun CrmOpportunityEntity.toUi(contacts: Map<String, ContactEntity>) = CrmOpportunityUi(
    entity = this,
    contactName = primaryContactId?.let(contacts::get)?.displayName ?: "主要联系人待确认",
)

private fun CrmNextActionEntity.toUi(opportunities: Map<String, CrmOpportunityEntity>, contacts: Map<String, ContactEntity>): CrmActionUi? {
    val opportunity = opportunities[opportunityId] ?: return null
    return CrmActionUi(
        entity = this,
        opportunityTitle = opportunity.title,
        accountName = opportunity.accountNameSnapshot,
        contactName = contactId?.let(contacts::get)?.displayName ?: "联系人待确认",
    )
}

private fun CrmAgentSuggestionEntity.toUi(opportunities: Map<String, CrmOpportunityEntity>, contacts: Map<String, ContactEntity>): CrmSuggestionUi {
    // Contact-scoped suggestions (NEW_LEAD) have no opportunity; opportunity-scoped ones resolve it.
    val opportunity = opportunityId?.let { opportunities[it] }
    return CrmSuggestionUi(this, opportunity?.title, opportunity?.accountNameSnapshot, contactId?.let { contacts[it]?.displayName })
}
