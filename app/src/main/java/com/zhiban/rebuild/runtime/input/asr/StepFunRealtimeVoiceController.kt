package com.zhiban.rebuild.runtime.input.asr

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Base64
import com.zhiban.rebuild.runtime.provider.CredentialResolver
import com.zhiban.rebuild.runtime.provider.OutboundChannel
import com.zhiban.rebuild.runtime.provider.OutboundExportDecision
import com.zhiban.rebuild.runtime.provider.OutboundExportDescriptor
import com.zhiban.rebuild.runtime.provider.OutboundExportGate
import com.zhiban.rebuild.runtime.provider.OutboundPurpose
import com.zhiban.rebuild.runtime.provider.OutboundSensitivity
import com.zhiban.rebuild.runtime.provider.ProviderProfileStore
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

sealed interface RealtimeVoiceState {
    data object Idle : RealtimeVoiceState
    data object Connecting : RealtimeVoiceState
    data class Recording(val partialText: String = "", val completedExchange: Completed? = null, val inputLevel: Float = 0f) : RealtimeVoiceState
    data class Responding(val transcript: String, val replyText: String = "") : RealtimeVoiceState
    data class Completed(val exchangeId: String, val transcript: String, val replyText: String) : RealtimeVoiceState
    data class Failed(val safeMessage: String) : RealtimeVoiceState
}

internal object StepFunRealtimeProtocol {
    fun sessionUpdate(): String = buildJsonObject {
        put("type", "session.update")
        put(
            "session",
            buildJsonObject {
                putJsonArray("modalities") {
                    add(JsonPrimitive("text"))
                    add(JsonPrimitive("audio"))
                }
                put("voice", "qingchunshaonv")
                put("input_audio_format", "pcm16")
                put("output_audio_format", "pcm16")
                put(
                    "input_audio_transcription",
                    buildJsonObject {
                        put("model", "stepaudio-2.5-asr")
                    },
                )
                put(
                    "turn_detection",
                    buildJsonObject {
                        put("type", "server_vad")
                        put("threshold", SERVER_VAD_THRESHOLD)
                        put("prefix_padding_ms", 300)
                        put("silence_duration_ms", 650)
                    },
                )
                put("instructions", "你是知伴，请用自然、温暖、简洁的中文与用户交流。")
            },
        )
    }.toString()

    const val CANCEL_RESPONSE = "{\"type\":\"response.cancel\"}"
}

/** One-key StepFun microphone -> model -> speaker session using the active Agent credential. */
@Singleton
class StepFunRealtimeVoiceController @Inject constructor(
    private val client: OkHttpClient,
    private val credentials: CredentialResolver,
    private val profiles: ProviderProfileStore,
    private val outboundGate: OutboundExportGate,
) {
    private val realtimeClient = realtimeWebSocketClient(client)
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<RealtimeVoiceState>(RealtimeVoiceState.Idle)
    val state: StateFlow<RealtimeVoiceState> = _state.asStateFlow()
    private val capturing = AtomicBoolean(false)
    private val responding = AtomicBoolean(false)
    private var socket: WebSocket? = null
    private var captureJob: Job? = null
    private var reconnectJob: Job? = null
    private var recorder: AudioRecord? = null
    private var player: AudioTrack? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var transcript = StringBuilder()
    private var reply = StringBuilder()
    private var exchangeId = UUID.randomUUID().toString()
    private val connectionGeneration = AtomicLong(0)

    @Volatile private var activeReconnectAttempt = 0

    fun start() {
        if (_state.value !is RealtimeVoiceState.Idle &&
            _state.value !is RealtimeVoiceState.Completed &&
            _state.value !is RealtimeVoiceState.Failed
        ) {
            return
        }
        _state.value = RealtimeVoiceState.Connecting
        reconnectJob?.cancel()
        reconnectJob = null
        val generation = connectionGeneration.incrementAndGet()
        transcript = StringBuilder()
        reply = StringBuilder()
        exchangeId = UUID.randomUUID().toString()
        scope.launch {
            try {
                val decision = outboundGate.evaluate(
                    OutboundExportDescriptor(
                        requestId = "realtime-$exchangeId",
                        channel = OutboundChannel.ASR_REALTIME,
                        purpose = OutboundPurpose.USER_AUTHORED,
                        sensitivities = setOf(OutboundSensitivity.SENSITIVE),
                        payloadCount = 0,
                        attachmentCount = 1,
                    ),
                )
                if (decision != OutboundExportDecision.ALLOWED) {
                    _state.value = RealtimeVoiceState.Failed("请先在隐私与权限中允许语音识别上云")
                    return@launch
                }
                connect(generation, reconnectAttempt = 0)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                fail("实时语音连接失败")
            }
        }
    }

    private suspend fun connect(generation: Long, reconnectAttempt: Int) {
        if (generation != connectionGeneration.get()) return
        // Every newWebSocket must be consent-gated: scheduleReconnect() reaches this path without
        // start()'s audited evaluate(), so a mid-session consent revocation still has to block here.
        if (!outboundGate.consentGranted(OutboundChannel.ASR_REALTIME)) {
            fail("请先在隐私与权限中允许语音识别上云")
            return
        }
        try {
            val profile = profiles.load()
            if (profile?.providerId != "stepfun") {
                fail("请先连接阶跃星辰")
                return
            }
            credentials.withCredential(profile.credentialRef, profile.keyVersion) { secret ->
                if (generation != connectionGeneration.get()) return@withCredential
                val request = Request.Builder()
                    .url(
                        "wss://api.stepfun.com/v1/realtime?model=${com.zhiban.rebuild.runtime.provider.TrustedProviderRegistry.STEPFUN_REALTIME_MODEL}",
                    )
                    .header("Authorization", "Bearer ${secret.decodeToString()}")
                    .build()
                activeReconnectAttempt = reconnectAttempt
                socket = realtimeClient.newWebSocket(request, Listener(generation))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            scheduleReconnect(generation, reconnectAttempt, "实时语音连接失败")
        }
    }

    fun finishInput() {
        // In realtime-call mode the server VAD commits each turn automatically.
        // The square button therefore ends the call instead of committing a
        // single push-to-talk utterance.
        cancel()
    }

    fun cancel() {
        connectionGeneration.incrementAndGet()
        reconnectJob?.cancel()
        reconnectJob = null
        capturing.set(false)
        captureJob?.cancel()
        captureJob = null
        releaseCaptureResources()
        responding.set(false)
        releasePlayer()
        socket?.close(1000, "client_cancel")
        socket = null
        _state.value = RealtimeVoiceState.Idle
    }

    @SuppressLint("MissingPermission")
    private fun beginCapture() {
        val minimum = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minimum <= 0) return fail("无法启动麦克风")
        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minimum, FRAME_BYTES * 2),
        )
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            return fail("无法启动麦克风")
        }
        val effects = startRecordingWithEffects(audioRecord)
        if (effects == null) return fail("无法启动麦克风")
        val createdEchoCanceler = effects.echoCanceler
        val createdNoiseSuppressor = effects.noiseSuppressor
        if (_state.value is RealtimeVoiceState.Idle) {
            runCatching { createdEchoCanceler?.release() }
            runCatching { createdNoiseSuppressor?.release() }
            releaseAudioRecord(audioRecord)
            return
        }
        recorder = audioRecord
        echoCanceler = createdEchoCanceler
        noiseSuppressor = createdNoiseSuppressor
        capturing.set(true)
        _state.value = RealtimeVoiceState.Recording()
        captureJob = launchCaptureLoop(audioRecord)
    }

    private fun startRecordingWithEffects(audioRecord: AudioRecord): AudioEffects? {
        var createdEchoCanceler: AcousticEchoCanceler? = null
        var createdNoiseSuppressor: NoiseSuppressor? = null
        try {
            createdEchoCanceler = if (AcousticEchoCanceler.isAvailable()) {
                AcousticEchoCanceler.create(audioRecord.audioSessionId)?.apply { enabled = true }
            } else {
                null
            }
            createdNoiseSuppressor = if (NoiseSuppressor.isAvailable()) {
                NoiseSuppressor.create(audioRecord.audioSessionId)?.apply { enabled = true }
            } else {
                null
            }
            audioRecord.startRecording()
        } catch (cancelled: CancellationException) {
            runCatching { createdEchoCanceler?.release() }
            runCatching { createdNoiseSuppressor?.release() }
            releaseAudioRecord(audioRecord)
            throw cancelled
        } catch (_: Throwable) {
            runCatching { createdEchoCanceler?.release() }
            runCatching { createdNoiseSuppressor?.release() }
            releaseAudioRecord(audioRecord)
            return null
        }
        return AudioEffects(createdEchoCanceler, createdNoiseSuppressor)
    }

    private fun launchCaptureLoop(audioRecord: AudioRecord): Job = scope.launch {
        val buffer = ByteArray(FRAME_BYTES)
        var smoothedInputLevel = 0f
        var framesSinceLevelUpdate = 0
        try {
            while (capturing.get() && currentCoroutineContext().isActive) {
                val count = audioRecord.read(buffer, 0, buffer.size)
                if (isAudioReadFailure(count)) {
                    fail("麦克风权限已失效，请重新授权")
                    break
                } else if (count > 0) {
                    smoothedInputLevel = (
                        smoothedInputLevel * INPUT_LEVEL_HISTORY_WEIGHT +
                            measurePcm16InputLevel(buffer, count) * INPUT_LEVEL_SAMPLE_WEIGHT
                        ).coerceIn(0f, 1f)
                    framesSinceLevelUpdate += 1
                    if (framesSinceLevelUpdate >= 3) {
                        val current = _state.value
                        if (current is RealtimeVoiceState.Recording) {
                            _state.value = current.copy(inputLevel = smoothedInputLevel)
                        }
                        framesSinceLevelUpdate = 0
                    }
                    val encoded = Base64.encodeToString(buffer, 0, count, Base64.NO_WRAP)
                    val activeSocket = socket
                    val sent = activeSocket?.send(
                        buildJsonObject {
                            put("type", "input_audio_buffer.append")
                            put("audio", encoded)
                        }.toString(),
                    ) == true
                    if (!sent) {
                        if (activeSocket != null) {
                            handleDisconnect(activeSocket, connectionGeneration.get(), "实时语音连接失败")
                        } else {
                            fail("实时语音连接失败")
                        }
                        break
                    }
                }
            }
        } finally {
            buffer.fill(0)
        }
    }

    private data class AudioEffects(val echoCanceler: AcousticEchoCanceler?, val noiseSuppressor: NoiseSuppressor?)

    private fun ensurePlayer(): AudioTrack {
        player?.let { return it }
        val created = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(
                maxOf(
                    AudioTrack.getMinBufferSize(
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                    ) * 2,
                    FRAME_BYTES * 2,
                ),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        return try {
            created.play()
            player = created
            created
        } catch (failure: Throwable) {
            runCatching { created.release() }
            throw failure
        }
    }

    private fun fail(message: String) {
        connectionGeneration.incrementAndGet()
        reconnectJob?.cancel()
        reconnectJob = null
        capturing.set(false)
        captureJob?.cancel()
        captureJob = null
        releaseCaptureResources()
        responding.set(false)
        releasePlayer()
        socket?.cancel()
        socket = null
        _state.value = RealtimeVoiceState.Failed(message)
    }

    private fun handleDisconnect(webSocket: WebSocket, generation: Long, exhaustedMessage: String) {
        if (generation != connectionGeneration.get() || socket !== webSocket) return
        socket = null
        webSocket.cancel()
        capturing.set(false)
        captureJob?.cancel()
        captureJob = null
        releaseCaptureResources()
        responding.set(false)
        releasePlayer()
        scheduleReconnect(generation, activeReconnectAttempt, exhaustedMessage)
    }

    private fun scheduleReconnect(generation: Long, failedAttempt: Int, exhaustedMessage: String) {
        if (generation != connectionGeneration.get()) return
        val plan = nextRealtimeReconnect(failedAttempt) ?: return fail(exhaustedMessage)
        _state.value = RealtimeVoiceState.Connecting
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(plan.delayMs)
            if (generation == connectionGeneration.get()) connect(generation, plan.attempt)
        }
    }

    private fun releasePlayer() {
        runCatching { player?.pause() }
        runCatching { player?.flush() }
        runCatching { player?.release() }
        player = null
    }

    private fun releaseCaptureResources() {
        val activeRecorder = recorder
        recorder = null
        val activeEchoCanceler = echoCanceler
        echoCanceler = null
        val activeNoiseSuppressor = noiseSuppressor
        noiseSuppressor = null
        runCatching { activeEchoCanceler?.release() }
        runCatching { activeNoiseSuppressor?.release() }
        activeRecorder?.let(::releaseAudioRecord)
    }

    private fun releaseAudioRecord(audioRecord: AudioRecord) {
        runCatching { audioRecord.stop() }
        runCatching { audioRecord.release() }
    }

    private inner class Listener(private val generation: Long) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (!isActiveRealtimeConnection(generation, connectionGeneration.get(), socket === webSocket)) {
                webSocket.close(1000, "stale_connection")
                return
            }
            reconnectJob = null
            activeReconnectAttempt = 0
            if (!webSocket.send(StepFunRealtimeProtocol.sessionUpdate())) {
                handleDisconnect(webSocket, generation, "实时语音连接失败")
                return
            }
            beginCapture()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!isActiveRealtimeConnection(generation, connectionGeneration.get(), socket === webSocket)) return
            val event = runCatching { json.parseToJsonElement(text) as JsonObject }.getOrNull() ?: return
            val type = (event["type"] as? JsonPrimitive)?.content.orEmpty()
            val delta = (event["delta"] as? JsonPrimitive)?.content.orEmpty()
            val completedTranscript = (event["transcript"] as? JsonPrimitive)?.content.orEmpty()
            when (type) {
                "input_audio_buffer.speech_started" -> {
                    // Barge-in: immediately stop local playback and cancel the
                    // in-flight model response while microphone capture remains
                    // active on the same WebSocket session.
                    if (responding.getAndSet(false)) {
                        webSocket.send(StepFunRealtimeProtocol.CANCEL_RESPONSE)
                    }
                    runCatching { player?.pause() }
                    releasePlayer()
                    _state.value = RealtimeVoiceState.Recording()
                }

                "conversation.item.input_audio_transcription.delta" -> {
                    transcript.append(delta)
                    val currentLevel = (_state.value as? RealtimeVoiceState.Recording)?.inputLevel ?: 0f
                    _state.value = RealtimeVoiceState.Recording(
                        partialText = transcript.toString(),
                        inputLevel = currentLevel,
                    )
                }

                "conversation.item.input_audio_transcription.completed" -> {
                    if (completedTranscript.isNotBlank()) {
                        transcript.clear()
                        transcript.append(completedTranscript)
                    }
                    _state.value = RealtimeVoiceState.Responding(transcript.toString(), reply.toString())
                }

                "response.audio_transcript.delta", "response.text.delta" -> {
                    responding.set(true)
                    reply.append(delta)
                    _state.value = RealtimeVoiceState.Responding(transcript.toString(), reply.toString())
                }

                "response.audio.delta" -> runCatching {
                    responding.set(true)
                    val pcm = Base64.decode(delta, Base64.DEFAULT)
                    try {
                        ensurePlayer().write(pcm, 0, pcm.size)
                    } finally {
                        pcm.fill(0)
                    }
                }.onFailure {
                    releasePlayer()
                    fail("实时语音播放失败")
                }

                "response.done" -> {
                    runCatching { player?.stop() }
                    releasePlayer()
                    responding.set(false)
                    val completed = RealtimeVoiceState.Completed(
                        exchangeId,
                        transcript.toString(),
                        reply.toString(),
                    )
                    transcript = StringBuilder()
                    reply = StringBuilder()
                    exchangeId = UUID.randomUUID().toString()
                    // Keep recorder and WebSocket alive for the next turn.
                    _state.value = RealtimeVoiceState.Recording(completedExchange = completed)
                }

                "error" -> {
                    // A late response.cancel can race with response.done. That
                    // protocol error must not tear down an otherwise healthy
                    // telephone-style session.
                    val error = event["error"] as? JsonObject
                    val message = (error?.get("message") as? JsonPrimitive)?.content.orEmpty()
                    val cancelRace = message.contains("cancel", ignoreCase = true) &&
                        !responding.get()
                    if (!cancelRace) fail("实时语音服务暂时不可用")
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            handleDisconnect(webSocket, generation, "实时语音连接失败")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            handleDisconnect(webSocket, generation, "实时语音连接已断开")
        }
    }

    private companion object {
        const val SAMPLE_RATE = 24_000

        // 20 ms PCM16 mono frames at 24 kHz, matching StepFun's realtime VAD
        // latency recommendation.
        const val FRAME_BYTES = 960
    }
}

internal data class RealtimeReconnectPlan(val attempt: Int, val delayMs: Long)

internal fun nextRealtimeReconnect(failedAttempt: Int): RealtimeReconnectPlan? {
    if (failedAttempt !in 0 until MAX_REALTIME_RECONNECT_ATTEMPTS) return null
    return RealtimeReconnectPlan(
        attempt = failedAttempt + 1,
        delayMs = REALTIME_RECONNECT_BASE_DELAY_MS shl failedAttempt,
    )
}

internal fun realtimeWebSocketClient(base: OkHttpClient): OkHttpClient = base.newBuilder().pingInterval(REALTIME_PING_SECONDS, TimeUnit.SECONDS).build()

internal fun isAudioReadFailure(count: Int): Boolean = count < 0

internal fun isActiveRealtimeConnection(callbackGeneration: Long, currentGeneration: Long, sameSocket: Boolean): Boolean =
    callbackGeneration == currentGeneration && sameSocket

private const val REALTIME_PING_SECONDS = 20L
private const val REALTIME_RECONNECT_BASE_DELAY_MS = 1_000L
private const val MAX_REALTIME_RECONNECT_ATTEMPTS = 3
private const val SERVER_VAD_THRESHOLD = 0.5
private const val INPUT_LEVEL_HISTORY_WEIGHT = 0.68f
private const val INPUT_LEVEL_SAMPLE_WEIGHT = 0.32f
private const val INPUT_LEVEL_VISUAL_GAIN = 4.5

internal fun measurePcm16InputLevel(buffer: ByteArray, byteCount: Int): Float {
    val clampedByteCount = byteCount.coerceIn(0, buffer.size)
    val usableBytes = clampedByteCount - clampedByteCount % 2
    if (usableBytes == 0) return 0f
    var squareSum = 0.0
    var sampleCount = 0
    var index = 0
    while (index < usableBytes) {
        val raw = (buffer[index].toInt() and 0xff) or (buffer[index + 1].toInt() shl 8)
        val sample = raw.toShort().toInt() / 32768.0
        squareSum += sample * sample
        sampleCount += 1
        index += 2
    }
    val rms = kotlin.math.sqrt(squareSum / sampleCount)
    // Speech RMS is normally much lower than peak amplitude. A gentle square-root
    // curve keeps quiet speech visible without making room noise look like a loud signal.
    return kotlin.math.sqrt((rms * INPUT_LEVEL_VISUAL_GAIN).coerceIn(0.0, 1.0)).toFloat()
}
