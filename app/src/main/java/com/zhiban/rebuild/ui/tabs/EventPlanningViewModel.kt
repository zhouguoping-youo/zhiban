package com.zhiban.rebuild.ui.tabs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhiban.rebuild.data.agent.AgentDataRepository
import com.zhiban.rebuild.data.calendar.ScheduleReminderScheduler
import com.zhiban.rebuild.data.calendar.SystemCalendarReader
import com.zhiban.rebuild.data.event.EventPlanningRepository
import com.zhiban.rebuild.runtime.runSuspendCatching
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class EventPlanDraft(val title: String, val startAtEpochMs: Long, val durationMinutes: Int, val location: String?, val note: String?)

@HiltViewModel
class EventPlanningViewModel @Inject constructor(
    private val planning: EventPlanningRepository,
    private val agentData: AgentDataRepository,
    private val systemCalendar: SystemCalendarReader,
    private val reminderScheduler: ScheduleReminderScheduler,
) : ViewModel() {
    private val actionMessage = MutableStateFlow<String?>(null)

    val state = combine(
        planning.observePlans(),
        planning.observeAllParticipants(),
        agentData.observeContacts(),
        actionMessage,
    ) { plans, participants, contacts, message ->
        EventPlanningState(
            plans = buildEventPlanUi(plans, participants, contacts),
            contacts = contacts,
            isLoading = false,
            actionMessage = message,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = EventPlanningState(),
    )

    fun createPlan(draft: EventPlanDraft, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            runSuspendCatching {
                planning.createPlan(
                    title = draft.title,
                    startAtEpochMs = draft.startAtEpochMs,
                    durationMinutes = draft.durationMinutes,
                    location = draft.location,
                    note = draft.note,
                )
            }.onSuccess(onCreated).onFailure { actionMessage.value = it.message ?: "没有保存成功" }
        }
    }

    fun addParticipant(planId: String, contactId: String) = launchAction("已添加联系人") {
        planning.addParticipant(planId, contactId)
    }

    fun removeParticipant(planId: String, contactId: String) = launchAction("已移除联系人") {
        check(planning.removeParticipant(planId, contactId)) { "联系人不在这次安排中" }
    }

    fun updateResponse(planId: String, contactId: String, responseStatus: String) = launchAction("回复状态已更新") {
        planning.updateResponse(planId, contactId, responseStatus)
    }

    fun confirmToCalendar(item: EventPlanUi) {
        viewModelScope.launch {
            val plan = item.plan
            val endAt = plan.proposedStartAtEpochMs + plan.durationMinutes * 60_000L
            val localConflict = agentData.findScheduleConflicts(
                plan.proposedStartAtEpochMs,
                plan.durationMinutes,
                plan.scheduleId,
            ).firstOrNull()
            val externalConflict = systemCalendar.findConflicts(
                plan.proposedStartAtEpochMs,
                endAt,
                plan.scheduleId,
                1,
            ).firstOrNull()
            val conflictTitle = localConflict?.title ?: externalConflict?.title
            if (conflictTitle != null) {
                actionMessage.value = "这段时间已有“$conflictTitle”"
                return@launch
            }
            runSuspendCatching { planning.confirmToCalendar(plan.planId) }
                .onSuccess { schedule ->
                    reminderScheduler.replace(
                        schedule.id,
                        schedule.startAtEpochMs,
                        schedule.reminderMinutesBefore,
                    )
                    actionMessage.value = "已加入日历"
                }
                .onFailure { actionMessage.value = it.message ?: "没有加入日历" }
        }
    }

    fun clearMessage() {
        actionMessage.value = null
    }

    fun deletePlan(item: EventPlanUi, onDeleted: () -> Unit) {
        viewModelScope.launch {
            val scheduleId = item.plan.scheduleId
            runSuspendCatching { planning.deletePlan(item.plan.planId) }
                .onSuccess { deleted ->
                    if (deleted) {
                        scheduleId?.let(reminderScheduler::cancel)
                        onDeleted()
                    } else {
                        actionMessage.value = "安排不存在"
                    }
                }
                .onFailure { actionMessage.value = it.message ?: "删除没有完成" }
        }
    }

    private fun launchAction(success: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            runSuspendCatching(block)
                .onSuccess { actionMessage.value = success }
                .onFailure { actionMessage.value = it.message ?: "操作没有完成" }
        }
    }
}
