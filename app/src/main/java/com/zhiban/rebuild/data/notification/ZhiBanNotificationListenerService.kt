package com.zhiban.rebuild.data.notification

import android.app.Notification
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.zhiban.rebuild.data.agent.AgentDataRepository
import com.zhiban.rebuild.foundation.runSuspendCatching
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

class ZhiBanNotificationListenerService : NotificationListenerService() {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    internal interface Dependencies {
        fun repository(): AgentDataRepository
        fun collectionPreferences(): MessageCollectionPreferences
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val repository by lazy {
        EntryPointAccessors.fromApplication(applicationContext, Dependencies::class.java).repository()
    }
    private val collectionPreferences by lazy {
        EntryPointAccessors.fromApplication(applicationContext, Dependencies::class.java).collectionPreferences()
    }
    private val notifications = Channel<StatusBarNotification>(
        capacity = NOTIFICATION_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            for (notification in notifications) {
                process(notification)
            }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        Log.d("ZhiBanNotif", "posted pkg=${sbn.packageName} key=${sbn.key} title=${sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE)}")
        if (!canProcess(sbn)) {
            Log.d("ZhiBanNotif", "skip pkg=${sbn.packageName} ongoing=${sbn.isOngoing}")
            return
        }
        notifications.trySend(sbn)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        val cutoff = System.currentTimeMillis() - ACTIVE_NOTIFICATION_RECOVERY_WINDOW_MS
        // Some Samsung builds deliver the connected callback before the listener
        // token is fully registered. Treat recovery as best-effort; newly posted
        // notifications continue through onNotificationPosted normally.
        val recentNotifications = runCatching {
            activeNotifications.orEmpty()
                .asSequence()
                .filter(::canProcess)
                .filter { it.postTime >= cutoff }
                .toList()
        }.getOrDefault(emptyList())
        scope.launch {
            collectionPreferences.markNotificationGapIfNeeded(
                nowEpochMs = System.currentTimeMillis(),
                thresholdMs = NOTIFICATION_GAP_THRESHOLD_MS,
            )
            repository.purgeNonPersonalSmsCandidates()
            recentNotifications.forEach { notifications.trySend(it) }
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        // 断连后系统可能立刻销毁本 Service，onDestroy 的 scope.cancel() 会取消刚派发的写协程，
        // 导致「断开时间戳」丢失、重连后的 gap 检测失效。这里用独立的一次性 scope 保证写盘完成。
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runSuspendCatching {
                collectionPreferences.onNotificationListenerDisconnected(System.currentTimeMillis())
            }
        }
        requestRebind(ComponentName(this, ZhiBanNotificationListenerService::class.java))
    }

    private fun canProcess(sbn: StatusBarNotification): Boolean = sbn.packageName != packageName &&
        SocialAppCatalog.isSupported(sbn.packageName) &&
        !sbn.isOngoing &&
        sbn.notification.category != Notification.CATEGORY_SERVICE

    private suspend fun process(sbn: StatusBarNotification) {
        Log.d("ZhiBanNotif", "process pkg=${sbn.packageName} key=${sbn.key}")
        val notification = sbn.notification
        val extras = notification.extras
        val appLabel = runSuspendCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        }.getOrDefault(sbn.packageName)
        val candidate = SocialNotificationParser.parse(
            SocialNotificationSnapshot(
                packageName = sbn.packageName,
                notificationKey = sbn.key,
                postTimeEpochMs = sbn.postTime,
                appLabel = appLabel,
                title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.clean(),
                text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.clean(),
                bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.clean(),
                conversationTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.clean(),
                selfDisplayName = messagingSelfName(extras),
                messages = SocialNotificationParser.messagingStyleMessages(extras),
                category = notification.category,
                isOngoing = sbn.isOngoing,
                userHandle = runSuspendCatching { sbn.user?.toString() }.getOrNull(),
            ),
        ) ?: run {
            Log.d("ZhiBanNotif", "parse returned null for ${sbn.key}")
            return
        }
        if (!collectionPreferences.isEnabled(candidate.platform)) {
            Log.d("ZhiBanNotif", "platform disabled: ${candidate.platform}")
            return
        }
        Log.d(
            "ZhiBanNotif",
            "stage candidate=${candidate.candidateId} platform=${candidate.platform} sender=${candidate.senderName} linked=${candidate.linkedContactId} suggested=${candidate.suggestedContactId}",
        )
        repository.stageNotificationCandidate(candidate)
    }

    override fun onDestroy() {
        notifications.close()
        scope.cancel()
        super.onDestroy()
    }

    private fun String.clean(): String = replace(Regex("\\s+"), " ").trim().take(MAX_NOTIFICATION_TEXT)

    @Suppress("DEPRECATION")
    private fun messagingSelfName(extras: android.os.Bundle): String? {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            val person = runCatching {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    extras.getParcelable(Notification.EXTRA_MESSAGING_PERSON, android.app.Person::class.java)
                } else {
                    extras.getParcelable(Notification.EXTRA_MESSAGING_PERSON) as? android.app.Person
                }
            }.getOrNull()
            person?.name?.toString()?.clean()?.let { return it }
        }
        return extras.getCharSequence(Notification.EXTRA_SELF_DISPLAY_NAME)?.toString()?.clean()
    }

    private companion object {
        const val NOTIFICATION_BUFFER_CAPACITY = 64
        const val ACTIVE_NOTIFICATION_RECOVERY_WINDOW_MS = 15 * 60_000L
        const val NOTIFICATION_GAP_THRESHOLD_MS = 30 * 60_000L
        const val MAX_NOTIFICATION_TEXT = 2_000
        const val WECHAT_PLATFORM_CODE = "WECHAT"
    }
}
