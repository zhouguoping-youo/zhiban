package com.zhiban.rebuild.ui.tabs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhiban.rebuild.data.agent.AgentDataRepository
import com.zhiban.rebuild.data.agent.ScheduleProjection
import com.zhiban.rebuild.data.calendar.ScheduleReminderScheduler
import com.zhiban.rebuild.data.calendar.SystemCalendarEvent
import com.zhiban.rebuild.data.calendar.SystemCalendarReader
import com.zhiban.rebuild.data.notification.NotificationCandidateEntity
import com.zhiban.rebuild.data.notification.ScheduleInsight
import com.zhiban.rebuild.runtime.input.asr.CloudAsrAvailability
import com.zhiban.rebuild.runtime.input.asr.CloudAsrGateway
import com.zhiban.rebuild.runtime.input.asr.CloudAsrResult
import com.zhiban.rebuild.runtime.runSuspendCatching
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CalendarAgentViewModel @Inject constructor(
    private val repository: AgentDataRepository,
    private val systemCalendarReader: SystemCalendarReader,
    private val reminderScheduler: ScheduleReminderScheduler,
    private val cloudAsrGateway: CloudAsrGateway,
) : ViewModel() {
    sealed interface SaveResult {
        data class Saved(val notificationPermissionNeeded: Boolean) : SaveResult
        data class Conflict(val message: String) : SaveResult
        data class Failed(val message: String) : SaveResult
    }
    data class ImportState(
        val isLoading: Boolean = false,
        val isImporting: Boolean = false,
        val events: List<SystemCalendarEvent> = emptyList(),
        val error: String? = null,
        val resultMessage: String? = null,
    )
    data class AuxiliaryUiState(val importState: ImportState = ImportState(), val cloudAsrAvailability: CloudAsrAvailability? = null)

    private val selectedDay = MutableStateFlow(LocalDate.now())
    private val currentDay = MutableStateFlow(LocalDate.now())
    private val mutableImportState = MutableStateFlow(ImportState())
    private val mutableCloudAsrAvailability = MutableStateFlow<CloudAsrAvailability?>(null)
    val importState = mutableImportState.asStateFlow()
    val cloudAsrAvailability = mutableCloudAsrAvailability.asStateFlow()
    val auxiliaryUiState = kotlinx.coroutines.flow.combine(importState, cloudAsrAvailability, ::AuxiliaryUiState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AuxiliaryUiState())
    val schedules: StateFlow<List<ScheduleProjection>> = selectedDay
        .flatMapLatest { day ->
            val zone = ZoneId.systemDefault()
            repository.observeSchedules(
                day.atStartOfDay(zone).toInstant().toEpochMilli(),
                day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1,
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val messageScheduleCandidates: StateFlow<List<NotificationCandidateEntity>> =
        repository.observeNotificationCandidates()
            .map { values ->
                values.filter { it.createdScheduleId == null && ScheduleInsight.from(it) != null }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val pendingFeedback: StateFlow<List<ScheduleProjection>> = currentDay
        .flatMapLatest { day ->
            val zone = ZoneId.systemDefault()
            repository.observePendingScheduleFeedback(
                beforeEpochMs = day.atStartOfDay(zone).toInstant().toEpochMilli(),
                oldestEpochMs = day.minusDays(PENDING_FEEDBACK_LOOKBACK_DAYS)
                    .atStartOfDay(zone).toInstant().toEpochMilli(),
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        refreshCloudAsrAvailability()
        viewModelScope.launch {
            while (true) {
                delay(CURRENT_DAY_REFRESH_MS)
                currentDay.value = LocalDate.now()
            }
        }
    }

    fun selectDay(day: LocalDate) {
        selectedDay.value = day
    }

    fun save(
        scheduleId: String?,
        title: String,
        startAtEpochMs: Long,
        durationMinutes: Int,
        note: String?,
        reminderMinutesBefore: Int?,
        allowConflict: Boolean,
        notificationsAllowed: Boolean,
        onResult: (SaveResult) -> Unit,
    ) {
        viewModelScope.launch {
            val conflictTitle = findConflictTitle(startAtEpochMs, durationMinutes, scheduleId)
            if (conflictTitle != null && !allowConflict) {
                onResult(SaveResult.Conflict("这段时间已有“$conflictTitle”"))
                return@launch
            }
            runSuspendCatching {
                repository.saveUserSchedule(
                    scheduleId,
                    title,
                    startAtEpochMs,
                    durationMinutes,
                    note,
                    reminderMinutesBefore,
                )
            }.onSuccess { id ->
                reminderScheduler.replace(id, startAtEpochMs, reminderMinutesBefore)
                onResult(SaveResult.Saved(reminderMinutesBefore != null && !notificationsAllowed))
            }.onFailure { onResult(SaveResult.Failed(it.message ?: "保存失败，请重试")) }
        }
    }

    fun delete(scheduleId: String, onDone: () -> Unit) {
        viewModelScope.launch {
            repository.deleteSchedule(scheduleId)
            reminderScheduler.cancel(scheduleId)
            onDone()
        }
    }

    fun complete(scheduleId: String, outcomeNote: String?, onDone: (Boolean) -> Unit) {
        viewModelScope.launch { onDone(repository.completeSchedule(scheduleId, outcomeNote)) }
    }

    fun reopen(scheduleId: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch { onDone(repository.reopenSchedule(scheduleId)) }
    }

    fun refreshCloudAsrAvailability() {
        viewModelScope.launch { mutableCloudAsrAvailability.value = cloudAsrGateway.availability() }
    }

    fun transcribeScheduleOutcome(audio: File, onResult: (String?, String?) -> Unit) {
        viewModelScope.launch {
            when (val result = cloudAsrGateway.transcribe(audio)) {
                is CloudAsrResult.Success -> {
                    audio.delete()
                    onResult(result.text, null)
                }

                is CloudAsrResult.Failure -> onResult(null, scheduleOutcomeAsrFailureMessage(result.safeCode))
            }
        }
    }

    fun loadSystemCalendar() {
        if (mutableImportState.value.isLoading) return
        viewModelScope.launch {
            mutableImportState.value = ImportState(isLoading = true)
            val today = LocalDate.now()
            val result = withContext(Dispatchers.IO) {
                systemCalendarReader.readRange(today.minusDays(30), today.plusDays(91))
            }
            mutableImportState.value = ImportState(events = result.events, error = result.errorMessage)
        }
    }

    fun importSystemCalendar(sourceIds: Set<String>) {
        if (sourceIds.isEmpty() || mutableImportState.value.isImporting) return
        viewModelScope.launch {
            val current = mutableImportState.value
            mutableImportState.value = current.copy(isImporting = true, error = null)
            runSuspendCatching {
                repository.importConfirmedSystemCalendarEvents(current.events.filter { it.sourceId in sourceIds })
            }.onSuccess { summary ->
                mutableImportState.value = current.copy(
                    isImporting = false,
                    resultMessage = "已导入 ${summary.created + summary.updated} 条日程" +
                        if (summary.updated > 0) "（其中 ${summary.updated} 条已更新）" else "",
                )
            }.onFailure {
                mutableImportState.value = current.copy(isImporting = false, error = it.message ?: "导入失败，请重试")
            }
        }
    }

    fun clearImportState() {
        mutableImportState.value = ImportState()
    }

    fun confirmMessageSchedule(candidate: NotificationCandidateEntity, notificationsAllowed: Boolean, onResult: (SaveResult) -> Unit) {
        viewModelScope.launch {
            val insight = ScheduleInsight.from(candidate)
                ?: return@launch onResult(SaveResult.Failed("这条内容没有完整的日期和时间"))
            val conflictTitle = findConflictTitle(insight.startAtEpochMs, insight.durationMinutes, null)
            if (conflictTitle != null) {
                onResult(SaveResult.Conflict("这段时间已有“$conflictTitle”"))
                return@launch
            }
            runSuspendCatching {
                val scheduleId = repository.confirmNotificationSchedule(candidate.candidateId)
                val schedule = repository.findSchedule(scheduleId) ?: error("日程没有保存成功")
                reminderScheduler.replace(
                    schedule.id,
                    schedule.startAtEpochMs,
                    schedule.reminderMinutesBefore,
                )
                schedule
            }.onSuccess { schedule ->
                onResult(
                    SaveResult.Saved(
                        schedule.reminderMinutesBefore != null && !notificationsAllowed,
                    ),
                )
            }.onFailure { onResult(SaveResult.Failed(it.message ?: "添加日程失败，请重试")) }
        }
    }

    fun dismissMessageCandidate(candidateId: String) {
        viewModelScope.launch { repository.dismissNotificationCandidate(candidateId) }
    }

    private suspend fun findConflictTitle(startAtEpochMs: Long, durationMinutes: Int, excludeScheduleId: String?): String? {
        repository.findScheduleConflicts(startAtEpochMs, durationMinutes, excludeScheduleId)
            .firstOrNull()
            ?.let { return it.title }
        val endAtEpochMs = Math.addExact(startAtEpochMs, durationMinutes * 60_000L)
        return systemCalendarReader
            .findConflicts(startAtEpochMs, endAtEpochMs, excludeScheduleId, limit = 1)
            .firstOrNull()
            ?.title
    }

    private companion object {
        const val PENDING_FEEDBACK_LOOKBACK_DAYS = 90L
        const val CURRENT_DAY_REFRESH_MS = 60_000L
    }
}

internal fun scheduleOutcomeAsrFailureMessage(safeCode: String): String = when (safeCode) {
    "ASR_CLOUD_CONSENT_REQUIRED" -> "请先在“我的－隐私与权限”中允许语音识别"
    "ASR_PROVIDER_NOT_CONFIGURED", "ASR_CREDENTIAL_UNAVAILABLE" -> "请先在“我的”中连接模型服务"
    "ASR_RATE_LIMITED" -> "语音服务繁忙，请稍后重试"
    "ASR_NETWORK_FAILURE", "ASR_PROVIDER_UNAVAILABLE" -> "网络不稳定，请重试或键盘输入"
    "AUDIO_EMPTY" -> "没有录到有效内容，请重试"
    else -> "语音识别失败，请重试或键盘输入"
}
