package com.zhiban.rebuild.data.notification

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.zhiban.rebuild.data.agent.AgentDataRepository
import com.zhiban.rebuild.data.communication.SmartForwardController
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Optional, user-enabled capture of messages the user visibly sends in supported social apps.
 *
 * The service never performs clicks and never writes directly to contacts/calendar. For apps that
 * hide their accessibility tree, it may take an ephemeral screenshot only while an exact,
 * process-local ZhiBan handoff expectation is pending. OCR runs on-device; the bitmap is never
 * persisted and is released immediately. A match enters the same local confirmation inbox.
 */
class OutgoingMessageAccessibilityService : AccessibilityService() {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun repository(): AgentDataRepository
        fun collectionPreferences(): MessageCollectionPreferences
        fun outgoingExpectationTracker(): OutgoingMessageExpectationTracker
        fun smartForwardController(): SmartForwardController
    }

    private data class Draft(val text: String, val changedAtEpochMs: Long)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())
    private val smartForwardPoll = object : Runnable {
        override fun run() {
            dependencies.smartForwardController().pollTimeout()
            handler.postDelayed(this, SMART_FORWARD_POLL_MS)
        }
    }
    private val drafts = mutableMapOf<String, Draft>()
    private val screenshotOcrInFlight = mutableSetOf<String>()
    private val textRecognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    @Volatile private var explicitlyEnabled = false

    private val dependencies by lazy {
        EntryPointAccessors.fromApplication(applicationContext, Dependencies::class.java)
    }

    override fun onServiceConnected() {
        handler.post(smartForwardPoll)
        scope.launch {
            dependencies.collectionPreferences().outgoingCollectionEnabled.collectLatest {
                explicitlyEnabled = it
                if (!it) synchronized(drafts) { drafts.clear() }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (dependencies.smartForwardController().onAccessibilityEvent(this, event)) return
        if (!explicitlyEnabled) return
        val packageName = event.packageName?.toString()?.takeIf(SocialAppCatalog::isSupported) ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> observeDraft(packageName, event)

            AccessibilityEvent.TYPE_VIEW_CLICKED -> if (isSendControl(event)) {
                SocialAppCatalog.platformForPackage(packageName)?.let { platform ->
                    dependencies.outgoingExpectationTracker()
                        .markSendClicked(platform.code, System.currentTimeMillis())
                }
                verifyAfterSend(packageName)
                verifyExpectedHandoff(packageName)
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> verifyExpectedHandoff(packageName)

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> verifyExpectedHandoff(packageName)

            else -> Unit
        }
    }

    private fun observeDraft(packageName: String, event: AccessibilityEvent) {
        val source = event.source ?: return
        val editable = source.isEditable && !source.isPassword
        source.recycle()
        if (!editable) return
        val text = event.text.joinToString("").replace(Regex("\\s+"), " ").trim().take(MAX_MESSAGE_CHARS)
        val now = System.currentTimeMillis()
        val previous = synchronized(drafts) { drafts[packageName] }
        if (text.isNotBlank()) {
            synchronized(drafts) { drafts[packageName] = Draft(text, now) }
        } else if (previous != null && now - previous.changedAtEpochMs <= MAX_DRAFT_AGE_MS) {
            verifyAfterSend(packageName, previous)
        }
    }

    private fun verifyAfterSend(packageName: String, knownDraft: Draft? = null) {
        val draft = knownDraft ?: synchronized(drafts) { drafts[packageName] } ?: return
        handler.postDelayed({
            if (!explicitlyEnabled) return@postDelayed
            val snapshot = visibleConversationSnapshot(packageName) ?: return@postDelayed
            if (snapshot.outgoingTexts.none { it == draft.text }) return@postDelayed
            synchronized(drafts) {
                if (drafts[packageName]?.text == draft.text) drafts.remove(packageName)
            }
            val candidate = outgoingAccessibilityCandidate(
                packageName = packageName,
                appLabel = appLabel(packageName),
                conversationTitle = snapshot.title,
                body = draft.text,
                postedAtEpochMs = System.currentTimeMillis(),
            ) ?: return@postDelayed
            scope.launch {
                if (dependencies.collectionPreferences().isEnabled(candidate.platform)) {
                    dependencies.repository().stageNotificationCandidate(candidate)
                }
            }
        }, SEND_CONFIRMATION_DELAY_MS)
    }

    private fun verifyExpectedHandoff(packageName: String) {
        val platform = SocialAppCatalog.platformForPackage(packageName) ?: return
        val tracker = dependencies.outgoingExpectationTracker()
        tracker.pending(platform.code, System.currentTimeMillis()) ?: return
        expectedHandoffCheck?.let(handler::removeCallbacks)
        expectedHandoffCheck = Runnable {
            if (!explicitlyEnabled) return@Runnable
            val current = tracker.pending(platform.code, System.currentTimeMillis()) ?: return@Runnable
            val snapshot = visibleConversationSnapshot(packageName)
            val normalizedExpected = current.message.normalizedVisibleMessage()
            if (snapshot != null &&
                snapshot.outgoingTexts.any { it.normalizedVisibleMessage() == normalizedExpected }
            ) {
                stageExpectationCandidate(packageName, current)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                scanExpectedHandoffScreenshot(packageName, platform.code)
            }
        }.also { handler.postDelayed(it, EXPECTED_HANDOFF_DEBOUNCE_MS) }
    }

    private fun stageExpectationCandidate(packageName: String, expectation: OutgoingMessageExpectationTracker.Expectation) {
        val tracker = dependencies.outgoingExpectationTracker()
        val candidate = outgoingAccessibilityCandidate(
            packageName = packageName,
            appLabel = appLabel(packageName),
            // This path only runs for a ZhiBan-initiated handoff whose recipient and body were
            // already shown to the user for confirmation. Do not replace that trusted recipient
            // with an OCR guess from the target app's toolbar.
            conversationTitle = expectation.recipient,
            body = expectation.message,
            postedAtEpochMs = System.currentTimeMillis(),
        ) ?: return
        tracker.consume(expectation)
        scope.launch {
            if (dependencies.collectionPreferences().isEnabled(candidate.platform)) {
                dependencies.repository().stageNotificationCandidate(candidate)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    @SuppressLint("NewApi")
    private fun scanExpectedHandoffScreenshot(packageName: String, platform: String) {
        val tracker = dependencies.outgoingExpectationTracker()
        if (tracker.pending(platform, System.currentTimeMillis()) == null) return
        val shouldStart = synchronized(screenshotOcrInFlight) {
            if (packageName in screenshotOcrInFlight) {
                false
            } else {
                screenshotOcrInFlight += packageName
                true
            }
        }
        if (!shouldStart) return
        runCatching {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        val bitmap = runCatching {
                            Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                                ?.copy(Bitmap.Config.ARGB_8888, false)
                        }.getOrNull()
                        screenshot.hardwareBuffer.close()
                        if (bitmap == null) {
                            finishScreenshotOcr(packageName)
                            return
                        }
                        recognizeExpectedHandoffScreenshot(packageName, platform, bitmap)
                    }

                    override fun onFailure(errorCode: Int) {
                        finishScreenshotOcr(packageName)
                    }
                },
            )
        }.onFailure {
            finishScreenshotOcr(packageName)
        }
    }

    private fun recognizeExpectedHandoffScreenshot(packageName: String, platform: String, bitmap: Bitmap) {
        val task = startResourceBoundOperation(
            start = { textRecognizer.process(InputImage.fromBitmap(bitmap, 0)) },
            releaseOnFailure = {
                bitmap.recycle()
                finishScreenshotOcr(packageName)
            },
        ) ?: return
        task
            .addOnSuccessListener { result ->
                val expectation = dependencies.outgoingExpectationTracker()
                    .pending(platform, System.currentTimeMillis())
                    ?: return@addOnSuccessListener
                val recognized = result.text.normalizedVisibleMessage()
                val expected = expectation.message.normalizedVisibleMessage()
                if (!recognized.contains(expected, ignoreCase = true)) return@addOnSuccessListener
                stageExpectationCandidate(packageName, expectation)
            }
            .addOnCompleteListener {
                bitmap.recycle()
                finishScreenshotOcr(packageName)
            }
    }

    private fun finishScreenshotOcr(packageName: String) {
        synchronized(screenshotOcrInFlight) {
            screenshotOcrInFlight.remove(packageName)
        }
    }

    private fun visibleConversationSnapshot(expectedPackageName: String): ConversationSnapshot? {
        val root = rootInActiveWindow ?: return null
        return try {
            if (root.packageName?.toString() != expectedPackageName) return null
            val screen = resources.displayMetrics
            val lines = mutableListOf<VisibleLine>()
            collectVisibleLines(root, lines, AccessibilityTraversalBudget(), depth = 0)
            val title = lines.asSequence()
                .filter { it.centerY in 24..(screen.heightPixels * 0.18f).toInt() }
                .filter { it.centerX in (screen.widthPixels * 0.22f).toInt()..(screen.widthPixels * 0.78f).toInt() }
                .map(VisibleLine::text)
                .firstOrNull { it.length in 1..80 && it !in TOP_BAR_WORDS && !isControlText(it) }
                ?: return null
            val outgoing = lines.asSequence()
                .filter { it.centerY > screen.heightPixels * 0.12f }
                .filter { it.right >= screen.widthPixels * 0.82f }
                .filter { it.left >= screen.widthPixels * 0.12f }
                .map(VisibleLine::text)
                .filterNot(::isControlText)
                .toSet()
            ConversationSnapshot(title, outgoing)
        } finally {
            root.recycle()
        }
    }

    private fun collectVisibleLines(node: AccessibilityNodeInfo, result: MutableList<VisibleLine>, budget: AccessibilityTraversalBudget, depth: Int) {
        if (!budget.enter(depth, result.size)) return
        node.text?.toString()?.replace(Regex("\\s+"), " ")?.trim()
            ?.take(MAX_MESSAGE_CHARS)?.takeIf(String::isNotBlank)?.let { text ->
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                if (!bounds.isEmpty) {
                    result += VisibleLine(
                        text = text,
                        centerX = bounds.centerX(),
                        centerY = bounds.centerY(),
                        left = bounds.left,
                        right = bounds.right,
                    )
                }
            }
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { child ->
                try {
                    collectVisibleLines(child, result, budget, depth + 1)
                } finally {
                    child.recycle()
                }
            }
        }
    }

    private fun isSendControl(event: AccessibilityEvent): Boolean = buildList {
        event.text.mapNotNullTo(this) { it?.toString()?.trim() }
        event.contentDescription?.toString()?.trim()?.let(::add)
    }.any { label -> SEND_LABELS.any { label.equals(it, true) || label.contains(it, true) } }

    private fun appLabel(packageName: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        dependencies.smartForwardController().abort()
        textRecognizer.close()
        scope.cancel()
        super.onDestroy()
    }

    private var expectedHandoffCheck: Runnable? = null

    private data class VisibleLine(val text: String, val centerX: Int, val centerY: Int, val left: Int, val right: Int)
    private data class ConversationSnapshot(val title: String, val outgoingTexts: Set<String>)

    private companion object {
        const val MAX_MESSAGE_CHARS = 2_000
        const val MAX_DRAFT_AGE_MS = 5 * 60_000L
        const val SEND_CONFIRMATION_DELAY_MS = 550L
        const val EXPECTED_HANDOFF_DEBOUNCE_MS = 300L
        const val SMART_FORWARD_POLL_MS = 500L
        val SEND_LABELS = setOf("发送", "send", "发出")
        val TOP_BAR_WORDS = setOf(
            "微信", "QQ", "TIM", "飞书", "Lark", "企业微信", "钉钉", "短信", "消息",
            "选择一个聊天", "选择聊天", "发送给", "转发给",
        )
        fun isControlText(value: String): Boolean = value.length > MAX_MESSAGE_CHARS || value in SEND_LABELS ||
            value in setOf("返回", "更多", "语音", "表情", "图片", "相册", "拍摄", "文件", "按住 说话")
    }
}

internal class AccessibilityTraversalBudget(private val maxNodes: Int = 1_500, private val maxDepth: Int = 64, private val maxVisibleLines: Int = 500) {
    private var visitedNodes = 0

    fun enter(depth: Int, visibleLineCount: Int): Boolean {
        if (depth > maxDepth || visibleLineCount >= maxVisibleLines || visitedNodes >= maxNodes) return false
        visitedNodes += 1
        return true
    }
}

private fun String.normalizedVisibleMessage(): String = replace(Regex("\\s+"), " ").trim()

internal fun <T> startResourceBoundOperation(start: () -> T, releaseOnFailure: () -> Unit): T? = try {
    start()
} catch (cancelled: CancellationException) {
    releaseOnFailure()
    throw cancelled
} catch (_: Throwable) {
    releaseOnFailure()
    null
}
