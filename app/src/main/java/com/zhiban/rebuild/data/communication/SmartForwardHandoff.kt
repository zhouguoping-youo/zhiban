package com.zhiban.rebuild.data.communication

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Result of the optional, one-shot accessibility handoff. It never means that a message was sent. */
enum class SmartForwardOutcome {
    PREFILLED,
    FALLBACK_SHARE,
    ABORTED,
}

/** Boundary used by completion flows so the existing share-sheet path remains the default. */
internal fun interface SmartForwardHandoff {
    suspend fun openComposer(recipientDisplayName: String, message: String): SmartForwardOutcome
}

/** No-op implementation used by tests and by users who keep the experimental setting disabled. */
internal object DisabledSmartForwardHandoff : SmartForwardHandoff {
    override suspend fun openComposer(recipientDisplayName: String, message: String): SmartForwardOutcome = SmartForwardOutcome.FALLBACK_SHARE
}

/**
 * Single-use bridge between the app and the user-enabled accessibility service.
 *
 * The service only receives a target name and draft. It never returns or stores chat message
 * text. It locates an exact, clickable contact label, opens the conversation, sets the editable
 * field, and stops before the send control. Every step has a bounded timeout and at most three
 * forward scrolls; any other outcome falls back to the existing share sheet.
 */
@Singleton
class SmartForwardController @Inject constructor(@ApplicationContext private val context: Context) {
    private val lock = Any()
    private var active: Session? = null

    suspend fun open(recipientDisplayName: String, message: String): SmartForwardOutcome {
        val target = recipientDisplayName.trim()
        val draft = message.trim()
        if (target.isEmpty() || draft.isEmpty()) return SmartForwardOutcome.FALLBACK_SHARE
        val deferred = CompletableDeferred<SmartForwardOutcome>()
        val session = Session(target, draft, deferred, SystemClock.elapsedRealtime())
        synchronized(lock) {
            if (active != null) return SmartForwardOutcome.FALLBACK_SHARE
            active = session
        }
        try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(WECHAT_PACKAGE)
                ?: return SmartForwardOutcome.FALLBACK_SHARE
            withContext(Dispatchers.Main.immediate) {
                context.startActivity(launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            return withTimeoutOrNull(OVERALL_TIMEOUT_MS) { deferred.await() }
                ?: SmartForwardOutcome.FALLBACK_SHARE
        } catch (cancelled: CancellationException) {
            complete(session, SmartForwardOutcome.ABORTED)
            throw cancelled
        } catch (_: Throwable) {
            complete(session, SmartForwardOutcome.FALLBACK_SHARE)
            return SmartForwardOutcome.FALLBACK_SHARE
        } finally {
            synchronized(lock) { if (active === session) active = null }
        }
    }

    /** Called by the accessibility service for one-shot workflow events only. */
    fun onAccessibilityEvent(service: AccessibilityService, event: AccessibilityEvent): Boolean {
        val session = synchronized(lock) { active } ?: return false
        val packageName = event.packageName?.toString()
        if (packageName != null && packageName != WECHAT_PACKAGE) {
            if (SystemClock.elapsedRealtime() - session.startedAt > STARTUP_GRACE_MS) {
                complete(session, SmartForwardOutcome.ABORTED)
            }
            return true
        }
        if (SystemClock.elapsedRealtime() - session.stepStartedAt > STEP_TIMEOUT_MS) {
            complete(session, SmartForwardOutcome.FALLBACK_SHARE)
            return true
        }
        val root = service.rootInActiveWindow ?: return true
        try {
            when (session.step) {
                Step.FIND_CONVERSATION -> processConversationStep(session, root)
                Step.FILL_INPUT -> processInputStep(session, root)
            }
        } finally {
            root.recycle()
        }
        return true
    }

    /** Polling gives the 8-second step timeout an exit even when the target app stops emitting events. */
    fun pollTimeout(nowElapsed: Long = SystemClock.elapsedRealtime()) {
        val session = synchronized(lock) { active } ?: return
        if (nowElapsed - session.stepStartedAt > STEP_TIMEOUT_MS) {
            complete(session, SmartForwardOutcome.FALLBACK_SHARE)
        }
    }

    fun abort() {
        synchronized(lock) { active }?.let { complete(it, SmartForwardOutcome.ABORTED) }
    }

    private fun processConversationStep(session: Session, root: AccessibilityNodeInfo) {
        val target = SmartForwardNodeLocator.findExactClickable(root, session.recipientDisplayName)
        if (target != null) {
            try {
                if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    session.step = Step.FILL_INPUT
                    session.stepStartedAt = SystemClock.elapsedRealtime()
                } else {
                    complete(session, SmartForwardOutcome.FALLBACK_SHARE)
                }
            } finally {
                target.recycle()
            }
            return
        }
        if (session.scrolls >= MAX_SCROLLS) {
            complete(session, SmartForwardOutcome.FALLBACK_SHARE)
            return
        }
        val scrollable = SmartForwardNodeLocator.findScrollable(root)
        if (scrollable == null || !scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
            complete(session, SmartForwardOutcome.FALLBACK_SHARE)
        } else {
            session.scrolls++
            session.stepStartedAt = SystemClock.elapsedRealtime()
            scrollable.recycle()
        }
    }

    private fun processInputStep(session: Session, root: AccessibilityNodeInfo) {
        val input = SmartForwardNodeLocator.findEditable(root)
        if (input == null) return
        try {
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, session.message)
            }
            if (input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
                complete(session, SmartForwardOutcome.PREFILLED)
            } else {
                complete(session, SmartForwardOutcome.FALLBACK_SHARE)
            }
        } finally {
            input.recycle()
        }
    }

    private fun complete(session: Session, outcome: SmartForwardOutcome) {
        session.deferred.complete(outcome)
    }

    private data class Session(
        val recipientDisplayName: String,
        val message: String,
        val deferred: CompletableDeferred<SmartForwardOutcome>,
        val startedAt: Long,
        var step: Step = Step.FIND_CONVERSATION,
        var stepStartedAt: Long = startedAt,
        var scrolls: Int = 0,
    )

    private enum class Step { FIND_CONVERSATION, FILL_INPUT }

    private companion object {
        const val WECHAT_PACKAGE = "com.tencent.mm"
        const val MAX_SCROLLS = 3
        const val STEP_TIMEOUT_MS = 8_000L
        const val OVERALL_TIMEOUT_MS = 24_000L
        const val STARTUP_GRACE_MS = 1_500L
    }
}

/** Strict, non-content locator used by the experimental path. It never collects chat lines. */
internal object SmartForwardNodeLocator {
    fun findExactClickable(root: AccessibilityNodeInfo, target: String): AccessibilityNodeInfo? {
        val normalized = target.trim()
        if (normalized.isEmpty()) return null
        return find(root, maxNodes = 1_500) { node ->
            if (!node.isClickable) return@find false
            listOfNotNull(node.text?.toString(), node.contentDescription?.toString(), node.viewIdResourceName)
                .asSequence()
                .map { it.substringAfterLast(':').trim() }
                .any { it == normalized }
        }
    }

    fun findEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? = find(root, maxNodes = 1_500) { it.isEditable && !it.isPassword }

    fun findScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? = find(root, maxNodes = 1_500) { it.isScrollable }

    private fun find(node: AccessibilityNodeInfo, maxNodes: Int, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (maxNodes <= 0) return null
        if (predicate(node)) return node
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val found = find(child, maxNodes - index - 1, predicate)
            if (found != null) {
                if (found !== child) child.recycle()
                return found
            }
            child.recycle()
        }
        return null
    }
}
