package com.zhiban.rebuild

import android.app.Application
import com.zhiban.rebuild.data.calllog.CallLogCollectionPreferences
import com.zhiban.rebuild.data.calllog.CallLogSyncWorker
import com.zhiban.rebuild.data.calllog.CallNoteAudioCache
import com.zhiban.rebuild.data.calllog.CallStateMonitor
import com.zhiban.rebuild.foundation.runSuspendCatching
import com.zhiban.rebuild.provider.ProviderEnvironmentManager
import com.zhiban.rebuild.runtime.context.AgentMaintenanceCoordinator
import com.zhiban.rebuild.runtime.context.AgentMaintenanceWorker
import com.zhiban.rebuild.runtime.input.AttachmentStagingStartup
import com.zhiban.rebuild.runtime.kernel.RuntimeCommandRunner
import com.zhiban.rebuild.ui.chat.PreferencesManager
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class ZhiBanApp : Application() {
    @Inject internal lateinit var runtimeCommandRunner: RuntimeCommandRunner

    @Inject internal lateinit var attachmentStagingStartup: AttachmentStagingStartup

    @Inject internal lateinit var preferencesManager: PreferencesManager

    @Inject internal lateinit var providerEnvironment: ProviderEnvironmentManager

    @Inject internal lateinit var agentMaintenance: AgentMaintenanceCoordinator

    @Inject internal lateinit var callLogPreferences: CallLogCollectionPreferences

    @Inject internal lateinit var callStateMonitor: CallStateMonitor
    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        attachmentStagingStartup.start()
        // Runtime recovery must not wait behind cloud health checks or maintenance. A killed app can
        // leave a confirmed write in EXECUTING/OBSERVING; starting the runner synchronously lets it
        // reclaim that lease as soon as it expires and prevents the active session from remaining
        // permanently locked behind a stale confirmation/execution card while offline.
        startRuntimeBeforeMaintenance(runtimeCommandRunner::start) {
            AgentMaintenanceWorker.schedule(this)
        }
        startupScope.launch {
            // One-way in-app migration: credential bytes never leave the process and the legacy field
            // is cleared only after the Keystore/profile write succeeds.
            runStartupAction {
                val legacyModel = preferencesManager.getModel()
                preferencesManager.consumeLegacyApiKey { bytes ->
                    providerEnvironment.configureStepFun(bytes, legacyModel)
                }
            }
            // Cold-start capability probe: a stored profile is not treated as reachable merely
            // because credential material exists. Failures stay safe-coded inside Agent provider.
            runStartupAction { providerEnvironment.healthCheck() }
            // Context cleanup is idempotent and deliberately cannot block Runtime availability.
            runStartupAction { agentMaintenance.run() }
            runStartupAction { CallNoteAudioCache.purgeExpired(this@ZhiBanApp) }
            if (runStartupAction { callLogPreferences.isEnabled() }.getOrDefault(false)) {
                CallLogSyncWorker.schedule(this@ZhiBanApp)
                if (runStartupAction { callLogPreferences.isHangupNoteEnabled() }.getOrDefault(false)) {
                    callStateMonitor.start()
                }
            }
        }
    }
}

internal suspend fun <T> runStartupAction(action: suspend () -> T): Result<T> = runSuspendCatching { action() }

internal inline fun startRuntimeBeforeMaintenance(startRuntime: () -> Unit, startMaintenance: () -> Unit) {
    startRuntime()
    startMaintenance()
}
