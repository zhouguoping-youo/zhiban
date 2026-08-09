package com.zhiban.rebuild.data.calllog

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Adds low-latency in-process callbacks; the manifest receiver and CallLog reconciliation remain the fallback. */
@Singleton
class CallStateMonitor @Inject constructor(@ApplicationContext private val context: Context) {
    private val registrations = mutableListOf<Pair<TelephonyManager, TelephonyCallback>>()

    @SuppressLint("MissingPermission")
    @Synchronized
    fun start() {
        if (Build.VERSION.SDK_INT < 31 || registrations.isNotEmpty()) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val base = context.getSystemService(TelephonyManager::class.java)
        val managers = runCatching {
            val subscriptions = context.getSystemService(SubscriptionManager::class.java)
                .activeSubscriptionInfoList.orEmpty()
            subscriptions.map { base.createForSubscriptionId(it.subscriptionId) }
                .ifEmpty { listOf(base) }
        }.getOrElse { listOf(base) }
        managers.forEach { manager ->
            val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    if (state == TelephonyManager.CALL_STATE_IDLE) enqueueCallHangupReconcile(context)
                }
            }
            runCatching {
                manager.registerTelephonyCallback(context.mainExecutor, callback)
                registrations += manager to callback
            }
        }
    }

    @Synchronized
    fun stop() {
        if (Build.VERSION.SDK_INT < 31) return
        registrations.toList().forEach { (manager, callback) ->
            runCatching { manager.unregisterTelephonyCallback(callback) }
        }
        registrations.clear()
    }
}
