package com.zhiban.rebuild.data.calendar

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.agent.ScheduleStatus
import com.zhiban.rebuild.data.event.EventResponseStatus
import com.zhiban.rebuild.data.suggestion.AgentSuggestionEntity
import com.zhiban.rebuild.data.suggestion.AgentSuggestionNotifier
import com.zhiban.rebuild.data.suggestion.AgentSuggestionStatus
import com.zhiban.rebuild.data.suggestion.AgentSuggestionType
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

class ScheduleAdvanceSuggestionWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val scheduleId = inputData.getString(KEY_SCHEDULE_ID) ?: return Result.failure()
        val expectedStartAt = inputData.getLong(KEY_START_AT, 0L)
        if (expectedStartAt <= 0L) return Result.failure()
        return try {
            val entryPoint = EntryPointAccessors.fromApplication(
                applicationContext,
                ScheduleAdvanceSuggestionWorkerEntryPoint::class.java,
            )
            entryPoint.scheduleAdvanceSuggestionCoordinator()
                .evaluate(scheduleId, expectedStartAt, System.currentTimeMillis())
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            android.util.Log.w(TAG, "schedule_advance:retry", failure)
            Result.retry()
        }
    }

    companion object {
        const val KEY_SCHEDULE_ID = "scheduleId"
        const val KEY_START_AT = "startAtEpochMs"
        private const val TAG = "ScheduleAdvanceWorker"
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface ScheduleAdvanceSuggestionWorkerEntryPoint {
    fun scheduleAdvanceSuggestionCoordinator(): ScheduleAdvanceSuggestionCoordinator
}

@Singleton
class ScheduleAdvanceSuggestionCoordinator @Inject internal constructor(private val database: AgentDatabase, private val notifier: AgentSuggestionNotifier) {
    suspend fun evaluate(scheduleId: String, expectedStartAtEpochMs: Long, nowEpochMs: Long): Boolean {
        val schedule = database.scheduleDao().findById(scheduleId) ?: return false
        if (schedule.status != ScheduleStatus.PENDING || schedule.startAtEpochMs != expectedStartAtEpochMs) return false
        val untilStart = schedule.startAtEpochMs - nowEpochMs
        if (untilStart !in DAY_MS..THREE_DAYS_MS) return false
        val pending = database.eventPlanningDao().participantsForSchedule(scheduleId)
            .filter { it.responseStatus == EventResponseStatus.PENDING }
        if (pending.isEmpty()) return false
        val names = database.contactDao().findByIds(pending.map { it.contactId })
            .map { it.displayName }
            .take(MAX_NAMES)
            .joinToString("、")
        val body = if (names.isBlank()) {
            "还有参与人未确认，要不要提前联系确认？"
        } else {
            "$names 还未确认，要不要提前联系确认？"
        }
        val suggestion = AgentSuggestionEntity(
            suggestionId = UUID.randomUUID().toString(),
            type = AgentSuggestionType.SCHEDULE_ADVANCE_CONFIRMATION,
            title = schedule.title,
            body = body,
            contactId = pending.singleOrNull()?.contactId,
            candidateId = scheduleId,
            sourceEvent = SOURCE_EVENT,
            dedupeKey = "schedule-advance:$scheduleId",
            status = AgentSuggestionStatus.PENDING,
            createdAtEpochMs = nowEpochMs,
            updatedAtEpochMs = nowEpochMs,
        )
        val inserted = database.agentSuggestionDao().insert(suggestion) != -1L
        if (inserted) notifier.publish(database.agentSuggestionDao().pendingCount(), suggestion.contactId, nowEpochMs)
        return inserted
    }

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1_000
        const val THREE_DAYS_MS = 3L * DAY_MS
        const val MAX_NAMES = 5
        const val SOURCE_EVENT = "SCHEDULE_ADVANCE_CHECK"
    }
}
