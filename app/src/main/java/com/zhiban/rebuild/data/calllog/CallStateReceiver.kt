package com.zhiban.rebuild.data.calllog

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** Receives only state changes. It never reads the incoming number from the broadcast. */
class CallStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!shouldEnqueueCallReconcile(
                intent.action,
                intent.getStringExtra(TelephonyManager.EXTRA_STATE),
            )
        ) {
            return
        }
        enqueueCallHangupReconcile(context)
    }
}

internal fun shouldEnqueueCallReconcile(action: String?, state: String?): Boolean = action == "android.intent.action.PHONE_STATE" && state == "IDLE"

internal fun enqueueCallHangupReconcile(context: Context) {
    val request = OneTimeWorkRequestBuilder<CallHangupReconcileWorker>()
        .setInitialDelay(2, TimeUnit.SECONDS)
        .build()
    WorkManager.getInstance(context).enqueueUniqueWork(
        "call-hangup-reconcile",
        ExistingWorkPolicy.REPLACE,
        request,
    )
}
