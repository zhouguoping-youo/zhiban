package com.zhiban.rebuild.data.calllog

import android.Manifest
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
import com.zhiban.rebuild.data.notification.NotificationCategory
import com.zhiban.rebuild.data.notification.NotificationCategoryPreferences
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException

class CallHangupReconcileWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val dependencies = EntryPointAccessors.fromApplication(
            applicationContext,
            CallHangupWorkerEntryPoint::class.java,
        )
        if (!dependencies.callLogPreferences().isEnabled() ||
            !dependencies.callLogPreferences().isHangupNoteEnabled() ||
            !dependencies.notificationCategoryPreferences().isEnabledNow(NotificationCategory.COLLECTION)
        ) {
            return Result.success()
        }
        return try {
            dependencies.callLogCoordinator().syncNow()
            val callId = dependencies.callLogRepository().markLatestCallPending() ?: return Result.success()
            postPrivatePrompt(callId)
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            Result.success()
        } catch (_: Throwable) {
            Result.retry()
        }
    }

    private fun postPrivatePrompt(callId: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "通话备注", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "通话结束后提醒补充要点"
                lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
            },
        )
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_CALL_NOTE, true)
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            callId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val publicVersion = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_agent_conversations)
            .setContentTitle("知伴通话提醒")
            .setContentText("解锁后查看")
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_agent_conversations)
            .setContentTitle("记录刚才的通话要点")
            .setContentText("打开知伴补充备注，不会录制通话内容")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .build()
        @Suppress("MissingPermission")
        NotificationManagerCompat.from(applicationContext).notify(callId.hashCode() and Int.MAX_VALUE, notification)
    }

    companion object {
        const val EXTRA_OPEN_CALL_NOTE = "openCallNote"
        private const val CHANNEL_ID = "call-note-reminders"
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface CallHangupWorkerEntryPoint {
    fun callLogCoordinator(): CallLogSyncCoordinator
    fun callLogRepository(): CallLogRepository
    fun callLogPreferences(): CallLogCollectionPreferences
    fun notificationCategoryPreferences(): NotificationCategoryPreferences
}
