package com.zhiban.rebuild

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.zhiban.rebuild.data.agent.AgentDataRepository
import com.zhiban.rebuild.data.calendar.ScheduleReminderWorker
import com.zhiban.rebuild.data.calllog.CallHangupReconcileWorker
import com.zhiban.rebuild.data.calllog.CallLogSyncCoordinator
import com.zhiban.rebuild.data.notification.ScreenshotVisionCandidateFormatter
import com.zhiban.rebuild.data.notification.ScreenshotVisionParser
import com.zhiban.rebuild.data.notification.sharedTextCandidate
import com.zhiban.rebuild.data.reply.ReplySuggestionCoordinator
import com.zhiban.rebuild.navigation.ZhiBanNavHost
import com.zhiban.rebuild.ui.theme.ThemePreferenceStore
import com.zhiban.rebuild.ui.theme.ZhiBanTheme
import com.zhiban.rebuild.ui.theme.resolvesToDarkTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var repository: AgentDataRepository

    @Inject lateinit var callLogSyncCoordinator: CallLogSyncCoordinator

    @Inject internal lateinit var replySuggestionCoordinator: ReplySuggestionCoordinator

    @Inject internal lateinit var contactCompletionCoordinator: com.zhiban.rebuild.data.completion.ContactCompletionCoordinator

    @Inject internal lateinit var screenshotVisionParser: ScreenshotVisionParser

    @Inject lateinit var themePreferenceStore: ThemePreferenceStore

    private val relationInboxRequest = mutableLongStateOf(0L)
    private val callNoteRequest = mutableLongStateOf(0L)
    private val calendarFocusRequest = mutableLongStateOf(0L)
    private val agentSuggestionsRequest = mutableLongStateOf(0L)
    private var textRecognizer: TextRecognizer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val themePreference by themePreferenceStore.preference.collectAsStateWithLifecycle()
            ZhiBanTheme(darkTheme = themePreference.resolvesToDarkTheme()) {
                ZhiBanNavHost(
                    relationInboxRequest = relationInboxRequest.longValue,
                    callNoteRequest = callNoteRequest.longValue,
                    calendarFocusRequest = calendarFocusRequest.longValue,
                    agentSuggestionsRequest = agentSuggestionsRequest.longValue,
                )
            }
        }
        acceptSharedContent(intent)
        acceptCallNoteRequest(intent)
        acceptScheduleReminderRequest(intent)
        acceptAgentSuggestionsRequest(intent)
        // 启动时主动扫掠关系推断(同公司同事边/互动推断),避免用户必须点进关系页才触发。
        lifecycleScope.launch { repository.sweepRelationshipInference() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptSharedContent(intent)
        acceptCallNoteRequest(intent)
        acceptScheduleReminderRequest(intent)
        acceptAgentSuggestionsRequest(intent)
    }

    private fun acceptCallNoteRequest(intent: Intent?) {
        if (intent?.getBooleanExtra(CallHangupReconcileWorker.EXTRA_OPEN_CALL_NOTE, false) == true) {
            callNoteRequest.longValue = System.currentTimeMillis()
            intent.removeExtra(CallHangupReconcileWorker.EXTRA_OPEN_CALL_NOTE)
        }
    }

    private fun acceptScheduleReminderRequest(intent: Intent?) {
        val startAtEpochMs = safeScheduleReminderEpoch(intent) ?: return
        calendarFocusRequest.longValue = startAtEpochMs
        intent?.removeExtra(ScheduleReminderWorker.EXTRA_OPEN_SCHEDULE_AT)
    }

    private fun acceptAgentSuggestionsRequest(intent: Intent?) {
        if (intent?.getBooleanExtra(com.zhiban.rebuild.data.suggestion.AgentSuggestionNotifier.EXTRA_OPEN_SUGGESTIONS, false) == true) {
            agentSuggestionsRequest.longValue = System.currentTimeMillis()
            intent.removeExtra(com.zhiban.rebuild.data.suggestion.AgentSuggestionNotifier.EXTRA_OPEN_SUGGESTIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { callLogSyncCoordinator.syncNow() }
        // T2: foreground sweep — catch capable-platform messages that arrived while the app was backgrounded
        // (e.g. the listener was briefly suspended). Cheap, debounced and conflated in the coordinator.
        replySuggestionCoordinator.onIncomingActivity()
        // 同一前台兜底也喂补全闭环:后台期间收到的"请补全资料"回复在此被补扫。
        contactCompletionCoordinator.onIncomingWechatActivity()
    }

    private fun acceptSharedContent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        when {
            intent.type?.startsWith("text/") == true -> acceptSharedText(intent)
            intent.type?.startsWith("image/") == true -> acceptSharedImage(intent)
        }
    }

    private fun resolveShareSource(intent: Intent): ShareSource {
        val sourcePackage = intent.getStringExtra(Intent.EXTRA_REFERRER_NAME)
            ?: referrer?.host
            ?: "manual-share"
        val sourceLabel = if (sourcePackage == "manual-share") {
            "手动分享"
        } else {
            runCatching {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(sourcePackage, 0)).toString()
            }.getOrNull() ?: "手动分享"
        }
        return ShareSource(sourcePackage, sourceLabel)
    }

    private fun acceptSharedText(intent: Intent) {
        val source = resolveShareSource(intent)
        val sharedText = safeSharedTextPayload(intent)
        val subject = safeSharedSubject(intent)
        val candidate = sharedTextCandidate(
            sourcePackage = source.packageName,
            sourceLabel = source.label,
            subject = if (sharedText.truncated) {
                listOfNotNull(subject, SHARED_TEXT_TRUNCATED_NOTICE).joinToString(" · ")
            } else {
                subject
            },
            body = sharedText.text,
        ) ?: return
        lifecycleScope.launch {
            repository.stageNotificationCandidate(candidate)
            relationInboxRequest.longValue = System.currentTimeMillis()
        }
    }

    private fun acceptSharedImage(intent: Intent) {
        val uri = getSharedImageUri(intent) ?: return
        lifecycleScope.launch {
            val recognized = recognizeImageText(uri)
            val visionCandidate = if (!recognized.isNullOrBlank() && ScreenshotVisionCandidateFormatter.isStructuredOcr(recognized)) {
                null
            } else {
                screenshotVisionParser.parse(uri.toString())
            }
            val candidateBody = visionCandidate ?: recognized ?: return@launch
            val source = resolveShareSource(intent)
            val candidate = sharedTextCandidate(
                sourcePackage = source.packageName,
                sourceLabel = source.label,
                subject = null,
                body = candidateBody,
            ) ?: return@launch
            repository.stageNotificationCandidate(candidate)
            relationInboxRequest.longValue = System.currentTimeMillis()
        }
    }

    private fun getSharedImageUri(intent: Intent): Uri? = safeSharedImageUri(intent)

    private suspend fun recognizeImageText(uri: Uri): String? {
        val bitmap = withContext(Dispatchers.IO) { decodeSharedImage(uri) } ?: return null
        val recognizer = textRecognizer ?: TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()).also {
            textRecognizer = it
        }
        return suspendCancellableCoroutine { continuation ->
            val task = runCatching { recognizer.process(InputImage.fromBitmap(bitmap, 0)) }.getOrElse {
                bitmap.recycle()
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }
            task.addOnSuccessListener { visionText ->
                if (continuation.isActive) continuation.resume(visionText.text.takeIf(String::isNotBlank))
            }.addOnFailureListener {
                if (continuation.isActive) continuation.resume(null)
            }.addOnCompleteListener {
                bitmap.recycle()
            }
        }
    }

    private fun decodeSharedImage(uri: Uri): Bitmap? {
        val declaredBytes = runCatching {
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        }.getOrNull() ?: UNKNOWN_SIZE
        if (!isSharedImageDeclaredSizeAllowed(declaredBytes)) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } }
            .getOrNull()
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateSharedImageSampleSize(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return runCatching { contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } }
            .getOrNull()
    }

    override fun onDestroy() {
        super.onDestroy()
        textRecognizer?.let {
            it.close()
        }
    }

    private data class ShareSource(val packageName: String, val label: String)
}

private const val UNKNOWN_SIZE = -1L
private const val MAX_SHARED_IMAGE_BYTES = 20L * 1024 * 1024
private const val MAX_SHARED_IMAGE_DIMENSION = 2_048
private const val MAX_SHARED_SUBJECT_BYTES = 1_024
private const val MAX_SHARED_TEXT_BYTES = 16 * 1_024
private const val SHARED_TEXT_TRUNCATED_NOTICE = "内容过长，已截取"
private const val UTF8_ASCII_BYTES = 1
private const val UTF8_TWO_BYTE_SEQUENCE = 2
private const val UTF8_THREE_BYTE_SEQUENCE = 3
private const val UTF8_FOUR_BYTE_SEQUENCE = 4
private const val UTF8_ONE_BYTE_MAX_CODE_POINT = 0x7F
private const val UTF8_TWO_BYTE_MAX_CODE_POINT = 0x7FF
private const val UTF8_THREE_BYTE_MAX_CODE_POINT = 0xFFFF

internal fun calculateSharedImageSampleSize(width: Int, height: Int): Int {
    require(width > 0 && height > 0) { "SHARED_IMAGE_DIMENSIONS_INVALID" }
    var sampleSize = 1
    while (maxOf(width, height) / sampleSize > MAX_SHARED_IMAGE_DIMENSION) sampleSize *= 2
    return sampleSize
}

internal fun isSharedImageDeclaredSizeAllowed(byteCount: Long): Boolean = byteCount == UNKNOWN_SIZE || byteCount in 0..MAX_SHARED_IMAGE_BYTES

internal fun safeSharedSubject(intent: Intent): String? = runCatching {
    boundedExternalText(intent.getStringExtra(Intent.EXTRA_SUBJECT), MAX_SHARED_SUBJECT_BYTES).text
}.getOrNull()

internal fun safeSharedText(intent: Intent): String? = safeSharedTextPayload(intent).text

internal fun safeSharedTextPayload(intent: Intent): BoundedExternalText = runCatching {
    boundedExternalText(intent.getCharSequenceExtra(Intent.EXTRA_TEXT), MAX_SHARED_TEXT_BYTES)
}.getOrDefault(BoundedExternalText(null, truncated = false))

internal data class BoundedExternalText(val text: String?, val truncated: Boolean)

/** Preserves valid Unicode while removing transport control characters and enforcing a UTF-8 byte cap. */
internal fun boundedExternalText(value: CharSequence?, maxUtf8Bytes: Int): BoundedExternalText {
    if (value == null) return BoundedExternalText(null, truncated = false)
    require(maxUtf8Bytes > 0)
    val output = StringBuilder(minOf(value.length, maxUtf8Bytes))
    var utf8Bytes = 0
    var index = 0
    while (index < value.length) {
        val sourceCodePoint = Character.codePointAt(value, index)
        val codePoint = when {
            sourceCodePoint in Character.MIN_SURROGATE.code..Character.MAX_SURROGATE.code -> 0xFFFD
            sourceCodePoint in ALLOWED_SHARED_TEXT_CONTROLS -> sourceCodePoint
            Character.isISOControl(sourceCodePoint) -> ' '.code
            else -> sourceCodePoint
        }
        val encodedBytes = codePoint.utf8Length()
        if (utf8Bytes + encodedBytes > maxUtf8Bytes) break
        output.appendCodePoint(codePoint)
        utf8Bytes += encodedBytes
        index += Character.charCount(sourceCodePoint)
    }
    return BoundedExternalText(
        text = output.toString().takeIf(String::isNotBlank),
        truncated = index < value.length,
    )
}

private fun Int.utf8Length(): Int = when {
    this <= UTF8_ONE_BYTE_MAX_CODE_POINT -> UTF8_ASCII_BYTES
    this <= UTF8_TWO_BYTE_MAX_CODE_POINT -> UTF8_TWO_BYTE_SEQUENCE
    this <= UTF8_THREE_BYTE_MAX_CODE_POINT -> UTF8_THREE_BYTE_SEQUENCE
    else -> UTF8_FOUR_BYTE_SEQUENCE
}

private val ALLOWED_SHARED_TEXT_CONTROLS = setOf('\t'.code, '\n'.code, '\r'.code)

internal fun safeSharedImageUri(intent: Intent): Uri? = runCatching {
    IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
}.getOrNull()

internal fun safeScheduleReminderEpoch(intent: Intent?): Long? = intent
    ?.getLongExtra(ScheduleReminderWorker.EXTRA_OPEN_SCHEDULE_AT, 0L)
    ?.takeIf { it > 0L }
