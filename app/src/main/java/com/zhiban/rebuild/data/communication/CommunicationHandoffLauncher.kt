package com.zhiban.rebuild.data.communication

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.zhiban.rebuild.data.notification.OutgoingMessageExpectationTracker
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class CommunicationHandoffResult(val platform: String, val status: String, val requiresUserSend: Boolean)

/**
 * Opens a platform-owned composer. It deliberately never reports SENT because personal messaging
 * apps do not provide ZhiBan with a trustworthy delivery receipt.
 */
@Singleton
class CommunicationHandoffLauncher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val outgoingExpectationTracker: OutgoingMessageExpectationTracker,
) {
    private val launchGuard = RecentHandoffLaunchGuard()

    fun open(platform: String, recipient: String, message: String): CommunicationHandoffResult {
        // In-process dedupe: an approval-retry / recover replay must not pop the target app a second
        // time. The primary guard is the idempotency record commit (NonCancellable in the binding);
        // this is a cheap backstop for any replay path that slips past it.
        val launchKey = "$platform|$recipient|$message"
        val reservation = launchGuard.reserve(launchKey)
        if (reservation == null) {
            return CommunicationHandoffResult(platform, "HANDOFF_OPENED", requiresUserSend = true)
        }

        try {
            val base = when (platform) {
                "SMS" -> Intent(
                    Intent.ACTION_SENDTO,
                    Uri.parse("smsto:${Uri.encode(recipient)}"),
                ).putExtra("sms_body", message).apply {
                    defaultSmsPackage()?.let(::setPackage)
                }

                else -> Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, message)
                    setPackage(PACKAGES.getValue(platform))
                }
            }
            // Resolve to an explicit component so the system can never show a disambiguation chooser —
            // implicit ACTION_SEND + setPackage still lets a chooser appear when the target package has
            // several exported handlers or the ROM intercepts the share sheet. Fail fast if unresolvable.
            val intent = resolveExplicit(base).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            outgoingExpectationTracker.record(
                platform = platform,
                recipient = recipient,
                message = message,
                nowEpochMs = System.currentTimeMillis(),
            )
            try {
                context.startActivity(intent)
            } catch (failure: Throwable) {
                outgoingExpectationTracker.clear()
                throw IllegalStateException("TARGET_APP_UNAVAILABLE", failure)
            }
            return CommunicationHandoffResult(
                platform = platform,
                status = "HANDOFF_OPENED",
                requiresUserSend = true,
            )
        } catch (failure: Throwable) {
            launchGuard.release(reservation)
            throw failure
        }
    }

    private fun resolveExplicit(intent: Intent): Intent {
        val matches = context.packageManager
            .queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .filter { intent.`package` == null || it.activityInfo.packageName == intent.`package` }
        val best = matches.firstOrNull { it.activityInfo.exported }
            ?: throw IllegalStateException("TARGET_APP_UNAVAILABLE")
        return Intent(intent).setClassName(best.activityInfo.packageName, best.activityInfo.name)
    }

    private fun defaultSmsPackage(): String? = runCatching {
        android.provider.Telephony.Sms.getDefaultSmsPackage(context)
    }.getOrNull()

    companion object {
        val SUPPORTED_PLATFORMS = setOf(
            "SMS",
            "WECHAT",
            "QQ",
            "TIM",
            "FEISHU",
            "LARK",
            "WEWORK",
            "DINGTALK",
        )
        private val PACKAGES = mapOf(
            "WECHAT" to "com.tencent.mm",
            "QQ" to "com.tencent.mobileqq",
            "TIM" to "com.tencent.tim",
            "FEISHU" to "com.ss.android.lark",
            "LARK" to "com.larksuite.suite",
            "WEWORK" to "com.tencent.wework",
            "DINGTALK" to "com.alibaba.android.rimet",
        )
    }
}

internal class RecentHandoffLaunchGuard(private val clock: () -> Long = System::currentTimeMillis) {
    private val reservations = mutableMapOf<String, Reservation>()

    @Synchronized
    fun reserve(key: String): Reservation? {
        val now = clock()
        reservations.entries.removeIf { now - it.value.reservedAtEpochMs > LAUNCH_GUARD_TTL_MS }
        if (key in reservations) return null
        return Reservation(key, now).also { reservations[key] = it }
    }

    @Synchronized
    fun release(reservation: Reservation) {
        reservations.remove(reservation.key, reservation)
    }

    internal data class Reservation(val key: String, val reservedAtEpochMs: Long)

    private companion object {
        const val LAUNCH_GUARD_TTL_MS = 2 * 60_000L
    }
}
