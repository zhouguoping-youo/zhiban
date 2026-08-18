package com.zhiban.rebuild.data.calllog

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

class CallLogSyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val dependencies = EntryPointAccessors.fromApplication(
            applicationContext,
            CallLogSyncWorkerEntryPoint::class.java,
        )
        return try {
            val result = dependencies.callLogCoordinator().syncNow()
            if (result.degradationReason == "call_log:failure") {
                android.util.Log.w(TAG, "call_log:sync_failure_retry")
                Result.retry()
            } else {
                Result.success()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            android.util.Log.w(TAG, "call_log:retry", failure)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "CallLogSyncWorker"
        private const val UNIQUE_NAME = "call-log-reconcile-v1"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<CallLogSyncWorker>(6, TimeUnit.HOURS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
        }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface CallLogSyncWorkerEntryPoint {
    fun callLogCoordinator(): CallLogSyncCoordinator
}
