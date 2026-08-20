package com.zhiban.rebuild.data.suggestion

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
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
import com.zhiban.rebuild.MainActivity
import com.zhiban.rebuild.R
import com.zhiban.rebuild.data.config.AgentControlStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AgentSuggestionNotifier @Inject constructor(@ApplicationContext private val context: Context, private val controls: AgentControlStore) {
    fun publish(pendingCount: Int, contactId: String?, nowEpochMs: Long = System.currentTimeMillis()) {
        if (pendingCount <= 0 || !canPublish(contactId, nowEpochMs)) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "知伴建议", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "提醒你查看知伴的新判断"
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            },
        )
        post(buildNotification(pendingCount))
    }

    private fun canPublish(contactId: String?, nowEpochMs: Long): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        return shouldPublishSuggestionNotification(
            hour = Instant.ofEpochMilli(nowEpochMs).atZone(ZoneId.systemDefault()).hour,
            interruptionFilter = manager.currentInterruptionFilter,
            contactOptedOut = contactId != null && (controls.isReplyOptedOut(contactId) || controls.isCompletionOptedOut(contactId)),
        )
    }

    private fun buildNotification(pendingCount: Int): Notification {
        val openIntent = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_OPEN_SUGGESTIONS, true)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pendingIntent = PendingIntent.getActivity(
            context,
            REQUEST_CODE,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val publicVersion = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_agent_conversations)
            .setContentTitle(PUBLIC_TEXT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_agent_conversations)
            .setContentTitle("知伴有 $pendingCount 条新判断")
            .setContentText("点击查看并决定下一步")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setNumber(pendingCount)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .build()
    }

    @SuppressLint("MissingPermission")
    private fun post(notification: Notification) {
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    companion object {
        const val EXTRA_OPEN_SUGGESTIONS = "com.zhiban.rebuild.extra.OPEN_AGENT_SUGGESTIONS"
        internal const val PUBLIC_TEXT = "知伴有新的建议"
        private const val CHANNEL_ID = "agent_suggestions"
        private const val NOTIFICATION_ID = 0xA63
        private const val REQUEST_CODE = 0xA63
    }
}

internal fun shouldPublishSuggestionNotification(hour: Int, interruptionFilter: Int, contactOptedOut: Boolean = false): Boolean =
    !contactOptedOut && hour in QUIET_HOURS_END until QUIET_HOURS_START &&
        interruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL

private const val QUIET_HOURS_START = 22
private const val QUIET_HOURS_END = 7
