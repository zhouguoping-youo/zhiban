package com.zhiban.rebuild.ui.tabs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhiban.rebuild.data.agent.AgentDataRepository
import com.zhiban.rebuild.data.calendar.ScheduleReminderScheduler
import com.zhiban.rebuild.data.calendar.SystemCalendarReader
import com.zhiban.rebuild.foundation.runSuspendCatching
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class LifeAssistantViewModel @Inject constructor(
    private val repository: AgentDataRepository,
    private val systemCalendarReader: SystemCalendarReader,
    private val reminderScheduler: ScheduleReminderScheduler,
) : ViewModel() {
    private val actionMessage = MutableStateFlow<String?>(null)

    val state = combine(
        repository.observeAllContactImportantDates(),
        repository.observeNotificationCandidates(),
        actionMessage,
    ) { dates, candidates, message ->
        LifeAssistantState(
            items = buildLifeAssistantItems(
                importantDates = dates,
                candidates = candidates,
                nowEpochMs = System.currentTimeMillis(),
                zoneId = ZoneId.systemDefault(),
            ),
            isLoading = false,
            actionMessage = message,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = LifeAssistantState(),
    )

    fun confirmCommitment(item: LifeAssistantItem) {
        val candidateId = item.candidateId ?: return
        viewModelScope.launch {
            val conflict = findConflictTitle(item.eventAtEpochMs, item.durationMinutes)
            if (conflict != null) {
                actionMessage.value = "这段时间已有“$conflict”"
                return@launch
            }
            runSuspendCatching {
                val scheduleId = repository.confirmNotificationSchedule(candidateId)
                val schedule = repository.findSchedule(scheduleId) ?: error("日程没有保存成功")
                reminderScheduler.replace(
                    schedule.id,
                    schedule.startAtEpochMs,
                    schedule.reminderMinutesBefore,
                )
            }.onSuccess {
                actionMessage.value = "已加入日历"
            }.onFailure {
                actionMessage.value = it.message ?: "添加失败，请重试"
            }
        }
    }

    fun dismissCommitment(item: LifeAssistantItem) {
        val candidateId = item.candidateId ?: return
        viewModelScope.launch {
            runSuspendCatching { repository.dismissNotificationCandidate(candidateId) }
                .onSuccess { dismissed -> actionMessage.value = if (dismissed) "已忽略" else "这条约定已经处理" }
                .onFailure { actionMessage.value = "操作失败，请重试" }
        }
    }

    fun clearActionMessage() {
        actionMessage.value = null
    }

    private suspend fun findConflictTitle(startAtEpochMs: Long, durationMinutes: Int): String? {
        repository.findScheduleConflicts(startAtEpochMs, durationMinutes).firstOrNull()?.let { return it.title }
        val endAtEpochMs = Math.addExact(startAtEpochMs, durationMinutes * 60_000L)
        return systemCalendarReader.findConflicts(startAtEpochMs, endAtEpochMs, null, 1).firstOrNull()?.title
    }
}
