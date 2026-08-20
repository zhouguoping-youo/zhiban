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
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.zhiban.rebuild.MainActivity
import com.zhiban.rebuild.R
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.notification.NotificationCategory
import com.zhiban.rebuild.data.notification.NotificationCategoryPreferences
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

internal object CallNoteFollowUpReminder {
    fun schedule(context: Context, callRecordId: String) {
        val request = OneTimeWorkRequestBuilder<CallNoteFollowUpWorker>()
            .setInitialDelay(FOLLOW_UP_DELAY_MS, TimeUnit.MILLISECONDS)
            .setInputData(Data.Builder().putString(CallNoteFollowUpWorker.KEY_CALL_RECORD_ID, callRecordId).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "call-note-follow-up:$callRecordId",
            ExistingWorkPolicy.KEEP,
            request,
        )
    }
}

class CallNoteFollowUpWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val callRecordId = inputData.getString(KEY_CALL_RECORD_ID) ?: return Result.success()
        val dependencies = EntryPointAccessors.fromApplication(
            applicationContext,
            CallNoteFollowUpEntryPoint::class.java,
        )
        if (!dependencies.callLogPreferences().isEnabled() ||
            !dependencies.callLogPreferences().isHangupNoteEnabled() ||
            !dependencies.notificationCategoryPreferences().isEnabledNow(NotificationCategory.COLLECTION)
        ) {
            return Result.success()
        }
        return try {
            if (dependencies.callNoteFollowUpCoordinator().shouldRemind(callRecordId)) {
                CallNoteNotificationPublisher.postFollowUp(applicationContext, callRecordId)
            }
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            android.util.Log.w(TAG, "call_note_follow_up:retry", failure)
            Result.retry()
        }
    }

    companion object {
        internal const val KEY_CALL_RECORD_ID = "callRecordId"
        private const val TAG = "CallNoteFollowUpWorker"
    }
}

@Singleton
class CallNoteFollowUpCoordinator @Inject internal constructor(private val database: AgentDatabase) {
    suspend fun shouldRemind(callRecordId: String, nowEpochMs: Long = System.currentTimeMillis()): Boolean {
        val call = database.callLogDao().findById(callRecordId) ?: return false
        return call.notePromptState == "PENDING" && nowEpochMs - call.updatedAtEpochMs >= FOLLOW_UP_DELAY_MS
    }
}

internal object CallNoteNotificationPublisher {
    fun postInitial(context: Context, callRecordId: String) = post(
        context,
        callRecordId,
        title = "记录刚才的通话要点",
        body = "打开知伴补充备注，不会录制通话内容",
    )

    fun postFollowUp(context: Context, callRecordId: String) = post(
        context,
        callRecordId,
        title = "还有一条通话备注待补充",
        body = "补充几句要点，之后查找这次沟通会更准确",
    )

    private fun post(context: Context, callRecordId: String, title: String, body: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "通话备注", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "通话结束后提醒补充要点"
                lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
            },
        )
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(CallHangupReconcileWorker.EXTRA_OPEN_CALL_NOTE, true)
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            callRecordId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val publicVersion = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_agent_conversations)
            .setContentTitle("知伴有新的建议")
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_agent_conversations)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .build()
        @Suppress("MissingPermission")
        NotificationManagerCompat.from(context).notify(callRecordId.hashCode() and Int.MAX_VALUE, notification)
    }

    private const val CHANNEL_ID = "call-note-reminders"
}

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface CallNoteFollowUpEntryPoint {
    fun callNoteFollowUpCoordinator(): CallNoteFollowUpCoordinator
    fun callLogPreferences(): CallLogCollectionPreferences
    fun notificationCategoryPreferences(): NotificationCategoryPreferences
}

internal const val FOLLOW_UP_DELAY_MS = 2L * 60 * 60_000L
