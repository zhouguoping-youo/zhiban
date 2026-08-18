package com.zhiban.rebuild.runtime.context

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

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface AgentMaintenanceEntryPoint {
    fun coordinator(): AgentMaintenanceCoordinator
}

internal class AgentMaintenanceWorker(appContext: Context, parameters: WorkerParameters) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result {
        val coordinator = EntryPointAccessors.fromApplication(
            applicationContext,
            AgentMaintenanceEntryPoint::class.java,
        ).coordinator()
        return try {
            coordinator.run()
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            // Worker 层静默 Result.retry() 补原因码日志(审计 M 类问题)。
            android.util.Log.w(TAG, "maintenance:retry", failure)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "AgentMaintenanceWorker"
        private const val UNIQUE_NAME = "agent-context-maintenance-v1"
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<AgentMaintenanceWorker>(12, TimeUnit.HOURS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
