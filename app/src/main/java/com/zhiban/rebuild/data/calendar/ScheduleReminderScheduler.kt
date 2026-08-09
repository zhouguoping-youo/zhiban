package com.zhiban.rebuild.data.calendar

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScheduleReminderScheduler @Inject constructor(@ApplicationContext private val context: Context) {
    fun replace(scheduleId: String, startAtEpochMs: Long, reminderMinutesBefore: Int?, nowEpochMs: Long = System.currentTimeMillis()) {
        val manager = WorkManager.getInstance(context)
        val uniqueName = uniqueName(scheduleId)
        if (reminderMinutesBefore == null) {
            manager.cancelUniqueWork(uniqueName)
            return
        }
        val delayMillis = reminderDelayMillis(startAtEpochMs, reminderMinutesBefore, nowEpochMs)
        if (delayMillis == null) {
            manager.cancelUniqueWork(uniqueName)
            return
        }
        val request = OneTimeWorkRequestBuilder<ScheduleReminderWorker>()
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .setInputData(reminderWorkData(scheduleId, startAtEpochMs, reminderMinutesBefore))
            .addTag(TAG)
            .build()
        manager.enqueueUniqueWork(uniqueName, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel(scheduleId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(uniqueName(scheduleId))
    }

    internal fun uniqueName(scheduleId: String) = "schedule-reminder-$scheduleId"

    companion object {
        const val TAG = "zhiban-schedule-reminder"
    }
}

/** WorkManager persists input data outside the encrypted app database, so it only receives IDs and validation fields. */
internal fun reminderWorkData(scheduleId: String, startAtEpochMs: Long, reminderMinutesBefore: Int): Data = Data.Builder()
    .putString(ScheduleReminderWorker.KEY_SCHEDULE_ID, scheduleId)
    .putLong(ScheduleReminderWorker.KEY_START_AT, startAtEpochMs)
    .putInt(ScheduleReminderWorker.KEY_REMINDER_MINUTES, reminderMinutesBefore)
    .build()

internal fun reminderDelayMillis(startAtEpochMs: Long, reminderMinutesBefore: Int, nowEpochMs: Long): Long? {
    if (startAtEpochMs <= nowEpochMs) return null
    val triggerAtEpochMs = startAtEpochMs - reminderMinutesBefore * 60_000L
    return (triggerAtEpochMs - nowEpochMs).coerceAtLeast(0L)
}
