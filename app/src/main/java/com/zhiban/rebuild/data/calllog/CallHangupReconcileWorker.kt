package com.zhiban.rebuild.data.calllog

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
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
            CallNoteNotificationPublisher.postInitial(applicationContext, callId)
            CallNoteFollowUpReminder.schedule(applicationContext, callId)
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            Result.success()
        } catch (failure: Throwable) {
            android.util.Log.w(TAG, "hangup_reconcile:retry", failure)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "CallHangupReconcileWorker"
        const val EXTRA_OPEN_CALL_NOTE = "openCallNote"
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
