package com.zhiban.rebuild.data.ilink

import com.zhiban.rebuild.data.ilink.network.IlinkBotTransport
import com.zhiban.rebuild.data.ilink.network.IlinkSessionExpiredException
import com.zhiban.rebuild.runtime.provider.ProviderFailure
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Notification-triggered inbound pull. The WeChat notification listener already fires the moment a
 * message arrives, but the notification text is truncated — so each WeChat notification triggers one
 * `getupdates` pull here to recover the full message, learn the sender's iLink `userId`, and cache the
 * conversation's `context_token` for reply threading.
 *
 * This is deliberately trigger-driven rather than a continuous long-poll loop: the listener is the
 * battery-cheap signal that there is something to fetch, so the phone is not holding a 35s poll open
 * around the clock. Triggers are conflated and debounced, so a burst of notifications collapses into
 * a single pull. Best-effort: a network or consent failure is swallowed (the truncated notification
 * candidate still stands), never propagated into the listener.
 */
@Singleton
internal class IlinkFetchCoordinator @Inject constructor(
    private val transport: IlinkBotTransport,
    private val gate: IlinkOutboundGate,
    private val credentialStore: IlinkBotCredentialStore,
    private val cursorStore: IlinkCursorStore,
    private val reconciler: NotificationReconciler,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val triggers = Channel<Unit>(capacity = Channel.CONFLATED)
    private val fetchMutex = Mutex()

    @Volatile
    private var consumerStarted = false

    /** Called when a WeChat notification was staged; schedules a debounced pull. Cheap and non-blocking. */
    fun onWechatMessageActivity() {
        ensureConsumerStarted()
        triggers.trySend(Unit)
    }

    /**
     * Force an immediate pull (the settings "sync now" action), bypassing the debounce. This is the
     * fallback for when no notification fires — e.g. WeChat is also logged in on a desktop, which by
     * default mutes the phone's message notifications so the listener never sees a trigger.
     */
    fun syncNow() {
        scope.launch {
            runCatching { fetchOnce() }
                .onFailure { if (it is CancellationException) throw it }
        }
    }

    @Synchronized
    private fun ensureConsumerStarted() {
        if (consumerStarted) return
        consumerStarted = true
        scope.launch {
            for (trigger in triggers) {
                delay(TRIGGER_DEBOUNCE_MS)
                runCatching { fetchOnce() }
                    .onFailure { if (it is CancellationException) throw it }
            }
        }
    }

    /** One pull pass: gate → session → getupdates → persist cursor → reconcile. Serialized by [fetchMutex]. */
    private suspend fun fetchOnce() {
        fetchMutex.withLock {
            gate.requireFetchAllowed("ilink-fetch-${System.currentTimeMillis()}")
            if (!credentialStore.hasUsableBinding()) return
            try {
                credentialStore.withSession { token, binding ->
                    val page = transport.getUpdates(token, binding.baseUrl, cursorStore.cursor())
                    if (page.getUpdatesBuf.isNotBlank()) cursorStore.saveCursor(page.getUpdatesBuf)
                    if (page.messages.isNotEmpty()) {
                        reconciler.reconcile(page.messages, System.currentTimeMillis())
                        credentialStore.markValidated(System.currentTimeMillis())
                    }
                }
            } catch (expired: IlinkSessionExpiredException) {
                // Session is dead and its cursor is tied to it; drop both so a re-bind starts clean.
                credentialStore.markSessionExpired()
                cursorStore.clear()
            } catch (_: ProviderFailure) {
                // Transient network/server failure: keep the cursor so the next trigger retries.
            }
        }
    }

    private companion object {
        const val TRIGGER_DEBOUNCE_MS = 1_500L
    }
}
