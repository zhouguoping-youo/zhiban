package com.zhiban.rebuild.ui.agent

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.rememberNavController
import com.zhiban.rebuild.navigation.Calendar
import com.zhiban.rebuild.runtime.input.asr.CloudAsrAvailability
import com.zhiban.rebuild.runtime.input.asr.CloudAsrResult
import com.zhiban.rebuild.runtime.input.asr.RealtimeVoiceState
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.math.sqrt
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
fun AgentConversationRoute(
    initialDraft: String = "",
    initialMode: String = "Chat",
    onBackToHome: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onManagePlugins: () -> Unit = {},
    viewModel: AgentConversationViewModel = hiltViewModel(),
) {
    BackHandler(onBack = onBackToHome)
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var input by rememberConversationDraftState()
    var multimodal by remember { mutableStateOf(MultimodalUiState()) }
    var voiceInputLevel by remember { mutableFloatStateOf(0f) }
    var pendingCaptureUri by remember { mutableStateOf<Uri?>(null) }
    var pendingCaptureFile by remember { mutableStateOf<File?>(null) }
    val capturedFiles = remember { mutableMapOf<String, File>() }
    var pendingPermissionAction by remember { mutableStateOf<CaptureAction?>(null) }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var recordingFile by remember { mutableStateOf<File?>(null) }
    var pendingRealtimePermission by remember { mutableStateOf(false) }
    var speechRecognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }
    var textToSpeech by remember { mutableStateOf<TextToSpeech?>(null) }
    val coroutineScope = rememberCoroutineScope()

    DisposableEffect(context) {
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) engine?.language = Locale.SIMPLIFIED_CHINESE
        }
        textToSpeech = engine
        onDispose {
            engine?.stop()
            engine?.shutdown()
            textToSpeech = null
        }
    }

    DisposableEffect(context) {
        val recognizer = if (SpeechRecognizer.isRecognitionAvailable(
                context,
            )
        ) {
            SpeechRecognizer.createSpeechRecognizer(context)
        } else {
            null
        }
        speechRecognizer = recognizer
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                voiceInputLevel = 0f
                multimodal = multimodal.copy(
                    transcription = TranscriptionUiState(TranscriptionPhase.RECORDING, originalAudioRetained = false),
                )
            }
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) {
                val measured = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                voiceInputLevel = (voiceInputLevel * 0.68f + measured * 0.32f).coerceIn(0f, 1f)
            }
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() {
                multimodal =
                    multimodal.copy(
                        transcription = multimodal.transcription.copy(phase = TranscriptionPhase.TRANSCRIBING),
                    )
            }
            override fun onError(error: Int) {
                voiceInputLevel = 0f
                multimodal = multimodal.copy(transcription = TranscriptionUiState(TranscriptionPhase.FAILED))
            }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (text.isBlank()) {
                    multimodal =
                        multimodal.copy(transcription = TranscriptionUiState(TranscriptionPhase.FAILED))
                } else {
                    input = text
                    multimodal =
                        multimodal.copy(
                            transcription = TranscriptionUiState(TranscriptionPhase.FINAL, finalText = text),
                        )
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults?.getStringArrayList(
                    SpeechRecognizer.RESULTS_RECOGNITION,
                )?.firstOrNull().orEmpty()
                multimodal = multimodal.copy(transcription = multimodal.transcription.copy(partialText = text))
            }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        onDispose {
            recognizer?.cancel()
            recognizer?.destroy()
            speechRecognizer = null
            recorder?.let { active ->
                runCatching { active.stop() }
                active.release()
            }
            recorder = null
            recordingFile?.delete()
            recordingFile = null
            capturedFiles.values.forEach { it.delete() }
            capturedFiles.clear()
            pendingCaptureFile?.delete()
            pendingCaptureFile = null
        }
    }

    fun appendAttachment(uri: Uri, modality: InputModality, fallbackName: String) {
        if (multimodal.attachments.items.size >= 5) {
            Toast.makeText(context, "一次最多添加 5 个附件", Toast.LENGTH_SHORT).show()
            return
        }
        val metadata = runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) {
                    null
                } else {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    Pair(
                        if (nameIndex >=
                            0
                        ) {
                            cursor.getString(nameIndex)
                        } else {
                            fallbackName
                        },
                        if (sizeIndex >=
                            0
                        ) {
                            cursor.getLong(sizeIndex)
                        } else {
                            0L
                        },
                    )
                }
            }
        }.getOrNull() ?: (fallbackName to 0L)
        val declaredMime = context.contentResolver.getType(uri).orEmpty()
        val genericMime = declaredMime.isBlank() || declaredMime == "application/octet-stream"
        val looksLikePdf = metadata.first?.endsWith(".pdf", ignoreCase = true) == true
        val validationMessage = when {
            metadata.second > 10L * 1024 * 1024 -> "附件超过 10 MB，请选择更小的文件。"

            modality == InputModality.IMAGE &&
                declaredMime !in setOf("image/jpeg", "image/png", "image/gif", "image/webp") ->
                "当前支持 JPG、PNG、GIF 和 WebP 图片。"

            modality == InputModality.FILE && declaredMime != "application/pdf" && !(genericMime && looksLikePdf) ->
                "当前文件识别支持 PDF。"

            else -> null
        }
        val item = AttachmentUiState(
            attachmentId = UUID.randomUUID().toString(),
            displayName = metadata.first ?: fallbackName,
            modality = modality,
            sourceUri = uri.toString(),
            phase = if (validationMessage == null) AttachmentPhase.SELECTED else AttachmentPhase.FAILED,
            totalBytes = metadata.second,
            safeMessage = validationMessage,
        )
        multimodal = multimodal.copy(attachments = AttachmentBatchUiState(multimodal.attachments.items + item))
    }

    fun newCaptureTarget(extension: String): Pair<Uri, File> {
        val file = File(context.cacheDir, "multimodal/capture_${System.currentTimeMillis()}.$extension").apply {
            parentFile?.mkdirs()
        }
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file) to file
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { appendAttachment(it, InputModality.IMAGE, "图片") }
    }
    val filePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { appendAttachment(it, InputModality.FILE, "文件") }
        }
    val photoCapture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        pendingCaptureUri?.let { uri ->
            if (success) {
                pendingCaptureFile?.let { capturedFiles[uri.toString()] = it }
                appendAttachment(uri, InputModality.IMAGE, "照片.jpg")
            } else {
                pendingCaptureFile?.delete()
            }
        }
        pendingCaptureUri = null
        pendingCaptureFile = null
    }
    fun startPhotoCapture() {
        val (uri, file) = newCaptureTarget("jpg")
        pendingCaptureUri = uri
        pendingCaptureFile = file
        photoCapture.launch(uri)
    }
    val cloudAsrAvailability by viewModel.cloudAsrAvailability.collectAsState()
    val realtimeVoiceState by viewModel.realtimeVoiceState.collectAsState()

    fun startSystemRecognition() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        voiceInputLevel = 0f
        multimodal = multimodal.copy(transcription = TranscriptionUiState(TranscriptionPhase.RECORDING))
        runCatching { requireNotNull(speechRecognizer).startListening(intent) }
            .onFailure { multimodal = multimodal.copy(transcription = TranscriptionUiState(TranscriptionPhase.FAILED)) }
    }
    fun failActiveRecording(activeRecorder: MediaRecorder, safeMessage: String) {
        if (recorder !== activeRecorder) return
        recorder = null
        val failedAudio = recordingFile
        recordingFile = null
        runCatching { activeRecorder.release() }
        failedAudio?.delete()
        voiceInputLevel = 0f
        multimodal = multimodal.copy(
            transcription = TranscriptionUiState(
                phase = TranscriptionPhase.FAILED,
                safeMessage = safeMessage,
                retryable = true,
            ),
        )
    }
    fun startRecording() {
        if (cloudAsrAvailability == CloudAsrAvailability.CONSENT_REQUIRED) {
            multimodal = multimodal.copy(
                transcription = TranscriptionUiState(
                    phase = TranscriptionPhase.FAILED,
                    safeMessage = "请先到“我的－隐私与权限”允许语音识别上云",
                    retryable = false,
                ),
            )
            return
        }
        if (cloudAsrAvailability != CloudAsrAvailability.AVAILABLE) {
            startSystemRecognition()
            return
        }
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
            startSystemRecognition()
            return
        }
        val file = File(context.cacheDir, "multimodal/voice_${System.currentTimeMillis()}.ogg").apply {
            parentFile?.mkdirs()
        }
        val next = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.OGG)
            setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
            setAudioChannels(1)
            setAudioSamplingRate(16_000)
            setOutputFile(file.absolutePath)
            setOnErrorListener { failedRecorder, _, _ ->
                coroutineScope.launch {
                    failActiveRecording(failedRecorder, RECORDING_INTERRUPTED_MESSAGE)
                }
            }
        }
        runCatching {
            next.prepare()
            next.start()
        }
            .onSuccess {
                recorder = next
                recordingFile = file
                voiceInputLevel = 0f
                multimodal =
                    multimodal.copy(
                        transcription = TranscriptionUiState(
                            TranscriptionPhase.RECORDING,
                            originalAudioRetained = true,
                        ),
                    )
            }
            .onFailure {
                next.release()
                file.delete()
                startSystemRecognition()
            }
    }
    fun transcribeRecordedAudio(audio: File) {
        multimodal =
            multimodal.copy(
                transcription = TranscriptionUiState(TranscriptionPhase.TRANSCRIBING, originalAudioRetained = true),
            )
        coroutineScope.launch {
            when (val result = viewModel.transcribeCloud(audio)) {
                is CloudAsrResult.Success -> {
                    input = result.text
                    multimodal =
                        multimodal.copy(
                            transcription = TranscriptionUiState(
                                TranscriptionPhase.FINAL,
                                finalText = result.text,
                                originalAudioRetained = false,
                            ),
                        )
                    audio.delete()
                    recordingFile = null
                }

                is CloudAsrResult.Failure -> {
                    recordingFile = audio
                    multimodal = multimodal.copy(
                        transcription = TranscriptionUiState(
                            phase = TranscriptionPhase.FAILED,
                            originalAudioRetained = true,
                            safeMessage = cloudAsrFailureMessage(result.safeCode),
                            retryable = result.retryable,
                        ),
                    )
                }
            }
        }
    }
    LaunchedEffect(recorder, multimodal.transcription.phase) {
        val activeRecorder = recorder
        if (activeRecorder != null && multimodal.transcription.phase == TranscriptionPhase.RECORDING) {
            var smoothedLevel = voiceInputLevel
            while (isActive && recorder === activeRecorder &&
                multimodal.transcription.phase == TranscriptionPhase.RECORDING
            ) {
                val permissionGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                val peakResult = runCatching { activeRecorder.maxAmplitude }
                val failureMessage = recordingHealthFailureMessage(permissionGranted, peakResult.isFailure)
                if (failureMessage != null) {
                    failActiveRecording(activeRecorder, failureMessage)
                    break
                }
                val peak = peakResult.getOrDefault(0)
                val measured = sqrt((peak / 32767f).coerceIn(0f, 1f))
                smoothedLevel = (smoothedLevel * 0.66f + measured * 0.34f).coerceIn(0f, 1f)
                voiceInputLevel = smoothedLevel
                delay(55)
            }
        }
    }
    fun stopRecording() {
        multimodal =
            multimodal.copy(transcription = multimodal.transcription.copy(phase = TranscriptionPhase.TRANSCRIBING))
        val activeRecorder = recorder
        val audio = recordingFile
        if (activeRecorder == null || audio == null) {
            speechRecognizer?.stopListening()
            return
        }
        recorder = null
        recordingFile = null
        val stopped = runCatching {
            activeRecorder.stop()
            activeRecorder.release()
        }.isSuccess
        if (!stopped) {
            runCatching { activeRecorder.release() }
            audio.delete()
            multimodal = multimodal.copy(transcription = TranscriptionUiState(TranscriptionPhase.FAILED))
            return
        }
        transcribeRecordedAudio(audio)
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = pendingPermissionAction
        pendingPermissionAction = null
        val permanentlyDenied = !granted && action != null && context.findActivity()?.let { activity ->
            !androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(activity, action.permission)
        } == true
        val permissionState = when {
            granted -> DevicePermissionState.GRANTED
            permanentlyDenied -> DevicePermissionState.PERMANENTLY_DENIED
            else -> DevicePermissionState.DENIED
        }
        multimodal = when (action) {
            CaptureAction.PHOTO -> multimodal.copy(cameraPermission = permissionState)
            CaptureAction.AUDIO -> multimodal.copy(microphonePermission = permissionState)
            null -> multimodal
        }
        if (granted && pendingRealtimePermission) {
            pendingRealtimePermission = false
            viewModel.startRealtimeVoice()
        } else {
            pendingRealtimePermission = false
            if (granted) {
                when (action) {
                    CaptureAction.PHOTO -> startPhotoCapture()
                    CaptureAction.AUDIO -> startRecording()
                    null -> Unit
                }
            }
        }
    }

    fun requestRealtimeVoice() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            viewModel.startRealtimeVoice()
        } else {
            pendingRealtimePermission = true
            pendingPermissionAction = CaptureAction.AUDIO
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // Stops any in-flight voice capture and clears the transcription status strip. Realtime voice
    // failures leave the controller Idle with a Failed UI state, so this must also reset the strip —
    // otherwise a failed realtime attempt shows a "删除录音" action for a recording that does not
    // exist and never dismisses, leaving the voice button looking dead.
    fun dismissVoiceFeedback() {
        viewModel.cancelRealtimeVoice()
        speechRecognizer?.cancel()
        recorder?.let { active ->
            runCatching { active.stop() }
            active.release()
        }
        recorder = null
        recordingFile?.delete()
        recordingFile = null
        voiceInputLevel = 0f
        multimodal = multimodal.copy(transcription = TranscriptionUiState())
    }

    DisposableEffect(Unit) { onDispose { viewModel.cancelRealtimeVoice() } }
    LaunchedEffect(realtimeVoiceState) {
        voiceInputLevel = (realtimeVoiceState as? RealtimeVoiceState.Recording)?.inputLevel ?: 0f
        multimodal = multimodal.copy(
            transcription = when (val voice = realtimeVoiceState) {
                RealtimeVoiceState.Idle -> if (
                    multimodal.transcription.phase in setOf(
                        TranscriptionPhase.RECORDING,
                        TranscriptionPhase.UPLOADING,
                        TranscriptionPhase.TRANSCRIBING,
                    )
                ) {
                    TranscriptionUiState()
                } else {
                    multimodal.transcription
                }

                RealtimeVoiceState.Connecting -> TranscriptionUiState(TranscriptionPhase.UPLOADING)

                is RealtimeVoiceState.Recording -> {
                    voice.completedExchange?.let { completed ->
                        viewModel.showRealtimeExchange(
                            completed.exchangeId,
                            completed.transcript,
                            completed.replyText,
                        )
                    }
                    TranscriptionUiState(
                        TranscriptionPhase.RECORDING,
                        partialText = voice.partialText,
                    )
                }

                is RealtimeVoiceState.Responding -> TranscriptionUiState(
                    TranscriptionPhase.TRANSCRIBING,
                    partialText = voice.transcript,
                )

                is RealtimeVoiceState.Completed -> {
                    viewModel.showRealtimeExchange(voice.exchangeId, voice.transcript, voice.replyText)
                    TranscriptionUiState(TranscriptionPhase.FINAL, finalText = voice.transcript)
                }

                is RealtimeVoiceState.Failed -> TranscriptionUiState(
                    phase = TranscriptionPhase.FAILED,
                    safeMessage = voice.safeMessage,
                    // Realtime retry re-opens the live session (no retained file), so it is safe to offer.
                    retryable = true,
                )
            },
        )
    }

    fun requestOrRun(action: CaptureAction) {
        if (ContextCompat.checkSelfPermission(context, action.permission) == PackageManager.PERMISSION_GRANTED) {
            multimodal = when (action) {
                CaptureAction.PHOTO -> multimodal.copy(cameraPermission = DevicePermissionState.GRANTED)
                CaptureAction.AUDIO -> multimodal.copy(microphonePermission = DevicePermissionState.GRANTED)
            }
            when (action) {
                CaptureAction.PHOTO -> startPhotoCapture()
                CaptureAction.AUDIO -> startRecording()
            }
        } else {
            multimodal = when (action) {
                CaptureAction.PHOTO -> multimodal.copy(cameraPermission = DevicePermissionState.REQUESTABLE)
                CaptureAction.AUDIO -> multimodal.copy(microphonePermission = DevicePermissionState.REQUESTABLE)
            }
            pendingPermissionAction = action
            permissionLauncher.launch(action.permission)
        }
    }

    LaunchedEffect(initialDraft, initialMode) { viewModel.initialize(initialDraft, initialMode) }
    val selectedModel by viewModel.selectedModel.collectAsState()
    val selectedMode by viewModel.selectedMode.collectAsState()
    val availableModels by viewModel.availableModels.collectAsState()
    val selectedLevel by viewModel.selectedLevel.collectAsState()
    val conversationHistory by viewModel.conversationHistory.collectAsState()
    AgentConversationScreen(
        state = state,
        voiceInputLevel = voiceInputLevel,
        inlineModelLabel = "$selectedModel 智能/$selectedLevel",
        onWorkTaskClick = { viewModel.plan(it) },
        onModelLabelClick = {},
        availableModels = availableModels,
        availableLevels = viewModel.availableLevels(),
        onModelSelect = viewModel::selectModel,
        onLevelSelect = viewModel::selectLevel,
        conversationHistory = conversationHistory,
        onLoadHistory = viewModel::loadConversationHistory,
        onOpenConversation = viewModel::openConversation,
        onDeleteConversation = viewModel::deleteConversation,
        onNewConversation = viewModel::newConversation,
        onBackToHome = onBackToHome,
        onNavigateToSettings = onNavigateToSettings,
        onManagePlugins = onManagePlugins,
        inputText = input,
        onInputChange = { input = it },
        onSend = { message ->
            val attachmentRefs = multimodal.attachments.items
                .filter { it.phase == AttachmentPhase.SELECTED || it.phase == AttachmentPhase.READY }
                .mapNotNull { it.sourceUri }
            viewModel.plan(
                message,
                mode = selectedMode,
                attachmentContentRefs = attachmentRefs,
                onAccepted = {
                    attachmentRefs.forEach { ref -> capturedFiles.remove(ref)?.delete() }
                    input = ""
                    multimodal = multimodal.copy(attachments = AttachmentBatchUiState())
                },
            )
        },
        onConfirm = viewModel::confirm,
        onReject = viewModel::reject,
        onRetry = viewModel::retry,
        onCancel = viewModel::cancel,
        onResume = viewModel::resume,
        onUndo = viewModel::undo,
        onCopyAssistant = {
            state.assistantMessage?.takeIf { it.isNotBlank() }?.let { reply ->
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                clipboard.setPrimaryClip(ClipData.newPlainText("知伴回复", reply))
                Toast.makeText(context, "已复制回复", Toast.LENGTH_SHORT).show()
            }
        },
        onReadAssistant = {
            state.assistantMessage?.takeIf { it.isNotBlank() }?.let { reply ->
                textToSpeech?.speak(reply, TextToSpeech.QUEUE_FLUSH, null, "zhiban-agent-reply")
            }
        },
        onShareAssistant = {
            state.assistantMessage?.takeIf { it.isNotBlank() }?.let { reply ->
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, reply)
                }
                context.startActivity(Intent.createChooser(share, "分享知伴回复"))
            }
        },
        onPositiveFeedback = {
            viewModel.positiveFeedback()
            Toast.makeText(context, "感谢反馈", Toast.LENGTH_SHORT).show()
        },
        onNegativeFeedback = {
            viewModel.negativeFeedback()
            Toast.makeText(context, "感谢反馈", Toast.LENGTH_SHORT).show()
        },
        multimodalState = multimodal,
        onPickImage = {
            imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        },
        onCapturePhoto = { requestOrRun(CaptureAction.PHOTO) },
        // The runtime currently validates and sends PDF documents end to end.
        // Do not offer unsupported types that would only fail after selection.
        onPickFile = { filePicker.launch(arrayOf("application/pdf")) },
        onToggleRecording = {
            when (realtimeVoiceState) {
                is RealtimeVoiceState.Recording -> viewModel.cancelRealtimeVoice()

                RealtimeVoiceState.Connecting, is RealtimeVoiceState.Responding -> viewModel.cancelRealtimeVoice()

                else -> if (multimodal.transcription.phase ==
                    TranscriptionPhase.RECORDING
                ) {
                    stopRecording()
                } else {
                    requestOrRun(CaptureAction.AUDIO)
                }
            }
        },
        onStartRealtimeVoice = {
            when (realtimeVoiceState) {
                is RealtimeVoiceState.Recording -> viewModel.cancelRealtimeVoice()
                RealtimeVoiceState.Connecting, is RealtimeVoiceState.Responding -> viewModel.cancelRealtimeVoice()
                else -> requestRealtimeVoice()
            }
        },
        onVoiceCancel = { dismissVoiceFeedback() },
        onVoiceRetry = {
            // Realtime voice has no retained recording: retry re-opens the live session.
            // Mic (batch) transcription retries the retained file.
            recordingFile?.let(::transcribeRecordedAudio) ?: run {
                dismissVoiceFeedback()
                requestRealtimeVoice()
            }
        },
        // Slice 1 (#t41): mic permission flow — when banner shows
        // PERMANENTLY_DENIED, route user to OS app-details page.
        onOpenAppSettings = { AppSettingsOpener.open(context) },
        onAttachmentAction = { id, action ->
            val items = multimodal.attachments.items
            if (action == AttachmentAction.DELETE || action == AttachmentAction.RESELECT) {
                items.firstOrNull { it.attachmentId == id }?.sourceUri?.let { capturedFiles.remove(it)?.delete() }
            }
            multimodal = when (action) {
                AttachmentAction.DELETE -> multimodal.copy(
                    attachments = AttachmentBatchUiState(
                        items.filterNot {
                            it.attachmentId ==
                                id
                        },
                    ),
                )

                AttachmentAction.RESELECT -> multimodal.copy(
                    attachments = AttachmentBatchUiState(
                        items.filterNot {
                            it.attachmentId ==
                                id
                        },
                    ),
                )

                AttachmentAction.CANCEL -> multimodal.copy(
                    attachments = AttachmentBatchUiState(
                        items.map {
                            if (it.attachmentId ==
                                id
                            ) {
                                it.copy(phase = AttachmentPhase.CANCELLED)
                            } else {
                                it
                            }
                        },
                    ),
                )

                AttachmentAction.RETRY -> multimodal.copy(
                    attachments = AttachmentBatchUiState(
                        items.map {
                            if (it.attachmentId ==
                                id
                            ) {
                                it.copy(phase = AttachmentPhase.SELECTED, safeMessage = null)
                            } else {
                                it
                            }
                        },
                    ),
                )
            }
        },
    )
}

@Composable
internal fun rememberConversationDraftState(): MutableState<String> = rememberSaveable { mutableStateOf("") }

private fun cloudAsrFailureMessage(code: String): String = when (code) {
    "ASR_EMPTY_RESULT", "AUDIO_EMPTY" -> "没有听清，请删除录音后重新说一次"
    "AUDIO_TOO_LARGE" -> "录音时间过长，请缩短后重新录音"
    "ASR_AUTHENTICATION_FAILED" -> "AI 服务连接已失效，请到设置中重新连接"
    "ASR_RATE_LIMITED" -> "语音请求过于频繁，请稍后重试"
    "ASR_PROVIDER_UNAVAILABLE" -> "语音服务暂时不可用，请稍后重试"
    "ASR_CLOUD_CONSENT_REQUIRED" -> "请先到“我的－隐私与权限”允许语音识别上云"
    "ASR_NETWORK_FAILURE" -> "网络连接失败，录音已保留"
    "ASR_PROVIDER_NOT_CONFIGURED" -> "请先在智能体设置中连接 AI 服务"
    "ASR_PROVIDER_UNSUPPORTED" -> "当前 AI 服务暂不支持语音转文字"
    else -> "没有完成转写，录音已保留"
}

private enum class CaptureAction(val permission: String) {
    PHOTO(Manifest.permission.CAMERA),
    AUDIO(Manifest.permission.RECORD_AUDIO),
}

internal const val RECORDING_INTERRUPTED_MESSAGE = "录音已中断，请重新授权后再试"

internal fun recordingHealthFailureMessage(permissionGranted: Boolean, recorderError: Boolean): String? =
    if (!permissionGranted || recorderError) RECORDING_INTERRUPTED_MESSAGE else null

private tailrec fun Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
