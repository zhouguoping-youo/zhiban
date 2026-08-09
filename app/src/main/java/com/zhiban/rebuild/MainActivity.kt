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
import com.zhiban.rebuild.data.calllog.CallHangupReconcileWorker
import com.zhiban.rebuild.data.calllog.CallLogSyncCoordinator
import com.zhiban.rebuild.data.notification.sharedTextCandidate
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

    @Inject lateinit var themePreferenceStore: ThemePreferenceStore

    private val relationInboxRequest = mutableLongStateOf(0L)
    private val callNoteRequest = mutableLongStateOf(0L)
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
                )
            }
        }
        acceptSharedContent(intent)
        acceptCallNoteRequest(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptSharedContent(intent)
        acceptCallNoteRequest(intent)
    }

    private fun acceptCallNoteRequest(intent: Intent?) {
        if (intent?.getBooleanExtra(CallHangupReconcileWorker.EXTRA_OPEN_CALL_NOTE, false) == true) {
            callNoteRequest.longValue = System.currentTimeMillis()
            intent.removeExtra(CallHangupReconcileWorker.EXTRA_OPEN_CALL_NOTE)
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { callLogSyncCoordinator.syncNow() }
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
        val candidate = sharedTextCandidate(
            sourcePackage = source.packageName,
            sourceLabel = source.label,
            subject = safeSharedSubject(intent),
            body = safeSharedText(intent),
        ) ?: return
        lifecycleScope.launch {
            repository.stageNotificationCandidate(candidate)
            relationInboxRequest.longValue = System.currentTimeMillis()
        }
    }

    private fun acceptSharedImage(intent: Intent) {
        val uri = getSharedImageUri(intent) ?: return
        lifecycleScope.launch {
            val recognized = recognizeImageText(uri) ?: return@launch
            val source = resolveShareSource(intent)
            val candidate = sharedTextCandidate(
                sourcePackage = source.packageName,
                sourceLabel = source.label,
                subject = null,
                body = recognized,
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

internal fun calculateSharedImageSampleSize(width: Int, height: Int): Int {
    require(width > 0 && height > 0) { "SHARED_IMAGE_DIMENSIONS_INVALID" }
    var sampleSize = 1
    while (maxOf(width, height) / sampleSize > MAX_SHARED_IMAGE_DIMENSION) sampleSize *= 2
    return sampleSize
}

internal fun isSharedImageDeclaredSizeAllowed(byteCount: Long): Boolean = byteCount == UNKNOWN_SIZE || byteCount in 0..MAX_SHARED_IMAGE_BYTES

internal fun safeSharedSubject(intent: Intent): String? = runCatching {
    intent.getStringExtra(Intent.EXTRA_SUBJECT)
}.getOrNull()

internal fun safeSharedText(intent: Intent): String? = runCatching {
    intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
}.getOrNull()

internal fun safeSharedImageUri(intent: Intent): Uri? = runCatching {
    IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
}.getOrNull()
