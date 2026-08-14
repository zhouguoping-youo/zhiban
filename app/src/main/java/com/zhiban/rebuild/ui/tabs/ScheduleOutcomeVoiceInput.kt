package com.zhiban.rebuild.ui.tabs

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.zhiban.rebuild.runtime.input.asr.CloudAsrAvailability
import java.io.File

@Composable
internal fun rememberScheduleOutcomeVoiceController(
    scheduleId: String,
    onTranscribe: (File, (String?, String?) -> Unit) -> Unit,
    onRecognized: (String) -> Unit,
): ScheduleOutcomeVoiceController {
    val context = LocalContext.current
    val controller = remember(scheduleId, context.cacheDir) {
        ScheduleOutcomeVoiceController(context)
    }
    SideEffect { controller.updateCallbacks(onTranscribe, onRecognized) }
    DisposableEffect(controller) { onDispose(controller::dispose) }
    return controller
}

@Composable
internal fun ScheduleOutcomeVoiceField(
    value: String,
    onValueChange: (String) -> Unit,
    availability: CloudAsrAvailability?,
    controller: ScheduleOutcomeVoiceController,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val microphonePermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), controller::onPermissionResult)
    Column(modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = {
                controller.clearError()
                onValueChange(it)
            },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            enabled = !controller.isBusy,
            label = { Text("结果或备注（可选）") },
            trailingIcon = {
                ScheduleOutcomeVoiceAction(controller) {
                    controller.onVoiceAction(
                        availability = availability,
                        hasPermission = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO,
                        ) == PackageManager.PERMISSION_GRANTED,
                        requestPermission = { microphonePermission.launch(Manifest.permission.RECORD_AUDIO) },
                    )
                }
            },
        )
        controller.statusMessage?.let { message ->
            Text(
                text = message,
                modifier = Modifier.padding(start = 16.dp, top = 6.dp, end = 16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = if (controller.hasError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ScheduleOutcomeVoiceAction(controller: ScheduleOutcomeVoiceController, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = !controller.isTranscribing, modifier = Modifier.size(48.dp)) {
        when {
            controller.isTranscribing -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            controller.isRecording -> Icon(Icons.Rounded.StopCircle, "结束录音并识别")
            controller.canRetry -> Icon(Icons.Rounded.Replay, "重新识别录音")
            else -> Icon(Icons.Outlined.Mic, "语音填写结果")
        }
    }
}

@Stable
internal class ScheduleOutcomeVoiceController(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var retryFile: File? = null
    private var disposed = false
    private var onTranscribe: (File, (String?, String?) -> Unit) -> Unit = { _, callback ->
        callback(null, "语音服务暂不可用")
    }
    private var onRecognized: (String) -> Unit = {}
    private var phase by mutableStateOf(VoicePhase.IDLE)
    private var error by mutableStateOf<String?>(null)

    val isRecording: Boolean get() = phase == VoicePhase.RECORDING
    val isTranscribing: Boolean get() = phase == VoicePhase.TRANSCRIBING
    val isBusy: Boolean get() = phase != VoicePhase.IDLE
    val canRetry: Boolean get() = retryFile?.isFile == true
    val hasError: Boolean get() = error != null
    val statusMessage: String?
        get() = error ?: when (phase) {
            VoicePhase.RECORDING -> "正在录音，点停止后转成文字"
            VoicePhase.TRANSCRIBING -> "正在识别…"
            VoicePhase.IDLE -> null
        }

    fun updateCallbacks(transcribe: (File, (String?, String?) -> Unit) -> Unit, recognized: (String) -> Unit) {
        onTranscribe = transcribe
        onRecognized = recognized
    }

    fun onVoiceAction(availability: CloudAsrAvailability?, hasPermission: Boolean, requestPermission: () -> Unit) {
        if (isRecording) {
            stopAndTranscribe()
            return
        }
        retryFile?.takeIf(File::isFile)?.let {
            transcribe(it)
            return
        }
        val unavailable = scheduleOutcomeVoiceUnavailableMessage(availability)
        if (unavailable != null) {
            error = unavailable
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            error = "当前系统版本暂不支持语音录入，请键盘输入"
        } else if (hasPermission) {
            startRecording()
        } else {
            requestPermission()
        }
    }

    fun onPermissionResult(granted: Boolean) {
        if (disposed) return
        if (granted) startRecording() else error = "需要麦克风权限才能录入语音"
    }

    fun clearError() {
        error = null
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        recorder?.let(::stopAndReleaseQuietly)
        recorder = null
        recordingFile?.delete()
        recordingFile = null
        retryFile?.delete()
        retryFile = null
    }

    private fun startRecording() {
        if (disposed) return
        val file = File(context.cacheDir, "schedule-outcomes/outcome_${System.currentTimeMillis()}.ogg").apply {
            parentFile?.mkdirs()
        }
        val started = acquireStartedResource(
            create = { createMediaRecorder(context) },
            start = { active -> configureAndStart(active, file) },
            release = MediaRecorder::release,
        ).getOrElse {
            file.delete()
            error = "无法开始录音，请键盘输入"
            return
        }
        disposed = false
        recorder = started
        recordingFile = file
        phase = VoicePhase.RECORDING
        error = null
    }

    private fun stopAndTranscribe() {
        val active = recorder ?: return
        val file = recordingFile
        val stopped = try {
            active.stop()
            true
        } catch (failure: RuntimeException) {
            Log.w(TAG, "schedule_voice:stop_failure", failure)
            false
        } finally {
            releaseQuietly(active)
            recorder = null
            recordingFile = null
        }
        if (!stopped || file == null || !file.isFile || file.length() == 0L) {
            file?.delete()
            phase = VoicePhase.IDLE
            error = "录音没有有效内容，请重试"
            return
        }
        transcribe(file)
    }

    private fun transcribe(file: File) {
        phase = VoicePhase.TRANSCRIBING
        error = null
        onTranscribe(file) { result, failure ->
            if (disposed) {
                file.delete()
            } else {
                phase = VoicePhase.IDLE
                if (!result.isNullOrBlank()) {
                    retryFile = null
                    onRecognized(result.trim())
                } else {
                    retryFile = file
                    error = failure ?: "语音识别失败，请重试或键盘输入"
                }
            }
        }
    }

    private fun stopAndReleaseQuietly(active: MediaRecorder) {
        try {
            active.stop()
        } catch (failure: RuntimeException) {
            Log.w(TAG, "schedule_voice:dispose_stop_failure", failure)
        }
        releaseQuietly(active)
    }

    private fun releaseQuietly(active: MediaRecorder) {
        try {
            active.release()
        } catch (failure: RuntimeException) {
            Log.w(TAG, "schedule_voice:release_failure", failure)
        }
    }

    private enum class VoicePhase { IDLE, RECORDING, TRANSCRIBING }

    private companion object {
        const val TAG = "ScheduleOutcomeVoice"
    }
}

internal fun scheduleOutcomeVoiceUnavailableMessage(availability: CloudAsrAvailability?): String? = when (availability) {
    null -> "正在检查语音服务，请稍候"
    CloudAsrAvailability.AVAILABLE -> null
    CloudAsrAvailability.CONSENT_REQUIRED -> "请先在“我的－隐私与权限”中允许语音识别"
    CloudAsrAvailability.PROVIDER_NOT_CONFIGURED -> "请先在“我的”中连接模型服务"
    CloudAsrAvailability.UNSUPPORTED_PROVIDER -> "当前模型服务暂不支持语音识别"
}

@Suppress("DEPRECATION")
private fun createMediaRecorder(context: Context): MediaRecorder =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()

private fun configureAndStart(recorder: MediaRecorder, output: File) {
    recorder.apply {
        setAudioSource(MediaRecorder.AudioSource.MIC)
        setOutputFormat(MediaRecorder.OutputFormat.OGG)
        setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
        setAudioChannels(1)
        setAudioSamplingRate(16_000)
        setOutputFile(output.absolutePath)
        prepare()
        start()
    }
}
