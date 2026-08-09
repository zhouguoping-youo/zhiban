package com.zhiban.rebuild.data.calendar

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.zhiban.rebuild.MainActivity
import com.zhiban.rebuild.R
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.agent.ScheduleEntity
import com.zhiban.rebuild.data.notification.NotificationCategory
import com.zhiban.rebuild.data.notification.NotificationCategoryPreferences
import com.zhiban.rebuild.ui.theme.DateFormats
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException

class ScheduleReminderWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val scheduleId = inputData.getString(KEY_SCHEDULE_ID) ?: return Result.failure()
        val startAt = inputData.getLong(KEY_START_AT, 0L)
        val reminderMinutes = inputData.getInt(KEY_REMINDER_MINUTES, INVALID_REMINDER_MINUTES)
        if (startAt <= 0L || reminderMinutes == INVALID_REMINDER_MINUTES) return Result.success()
        var currentSchedule: ScheduleEntity? = null
        val current = try {
            runIfReminderCurrent(
                scheduleId = scheduleId,
                expectedStartAtEpochMs = startAt,
                expectedReminderMinutes = reminderMinutes,
                loadSchedule = { id ->
                    EntryPointAccessors.fromApplication(
                        applicationContext,
                        ScheduleReminderWorkerEntryPoint::class.java,
                    ).agentDatabase().scheduleDao().findById(id)
                },
                onCurrent = { currentSchedule = it },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return Result.retry()
        }
        if (!current) return Result.success()
        val schedule = requireNotNull(currentSchedule)
        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext,
            ScheduleReminderWorkerEntryPoint::class.java,
        )
        if (!entryPoint.notificationCategoryPreferences().isEnabledNow(NotificationCategory.SCHEDULE)) {
            return Result.success()
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return Result.success()
        }

        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "日程提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "提醒你即将开始的日程"
            },
        )
        val time = Instant.ofEpochMilli(startAt).atZone(ZoneId.systemDefault())
            .format(DateFormats.Time)
        val notification = buildScheduleReminderNotification(applicationContext, schedule.title, time, startAt)
        postNotification(scheduleId.hashCode() and Int.MAX_VALUE, notification)
        return Result.success()
    }

    // doWork returns before this helper unless POST_NOTIFICATIONS is granted on Android 13+.
    @SuppressLint("MissingPermission")
    private fun postNotification(id: Int, notification: android.app.Notification) {
        NotificationManagerCompat.from(applicationContext).notify(id, notification)
    }

    companion object {
        const val KEY_SCHEDULE_ID = "scheduleId"
        const val KEY_START_AT = "startAtEpochMs"
        const val KEY_REMINDER_MINUTES = "reminderMinutesBefore"
        const val EXTRA_OPEN_SCHEDULE_AT = "openScheduleAtEpochMs"
        private const val INVALID_REMINDER_MINUTES = Int.MIN_VALUE
        private const val CHANNEL_ID = "schedule-reminders"
    }
}

/** The private notification may show details after unlock; its public version never reveals schedule content. */
internal fun buildScheduleReminderNotification(context: Context, title: String, formattedTime: String, startAtEpochMs: Long): android.app.Notification {
    val openScheduleIntent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra(ScheduleReminderWorker.EXTRA_OPEN_SCHEDULE_AT, startAtEpochMs)
    }
    val contentIntent = PendingIntent.getActivity(
        context,
        startAtEpochMs.hashCode(),
        openScheduleIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val publicVersion = NotificationCompat.Builder(context, "schedule-reminders")
        .setSmallIcon(R.drawable.ic_agent_conversations)
        .setContentTitle("知伴日程提醒")
        .setContentText("解锁后查看详情")
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .build()
    return NotificationCompat.Builder(context, "schedule-reminders")
        .setSmallIcon(R.drawable.ic_agent_conversations)
        .setContentTitle(title)
        .setContentText("$formattedTime 开始")
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .setPublicVersion(publicVersion)
        .setContentIntent(contentIntent)
        .setAutoCancel(true)
        .build()
}

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface ScheduleReminderWorkerEntryPoint {
    fun agentDatabase(): AgentDatabase
    fun notificationCategoryPreferences(): NotificationCategoryPreferences
}

internal fun matchesReminderSnapshot(schedule: ScheduleEntity?, expectedStartAtEpochMs: Long, expectedReminderMinutes: Int): Boolean = schedule != null &&
    schedule.startAtEpochMs == expectedStartAtEpochMs &&
    schedule.reminderMinutesBefore == expectedReminderMinutes

internal suspend fun runIfReminderCurrent(
    scheduleId: String,
    expectedStartAtEpochMs: Long,
    expectedReminderMinutes: Int,
    loadSchedule: suspend (String) -> ScheduleEntity?,
    onCurrent: (ScheduleEntity) -> Unit,
): Boolean {
    val schedule = loadSchedule(scheduleId)
    if (!matchesReminderSnapshot(schedule, expectedStartAtEpochMs, expectedReminderMinutes)) return false
    onCurrent(requireNotNull(schedule))
    return true
}
