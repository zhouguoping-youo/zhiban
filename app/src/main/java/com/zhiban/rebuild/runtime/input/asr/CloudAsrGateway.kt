package com.zhiban.rebuild.runtime.input.asr
import android.util.Base64
import com.zhiban.rebuild.runtime.provider.CredentialResolver
import com.zhiban.rebuild.runtime.provider.OutboundChannel
import com.zhiban.rebuild.runtime.provider.OutboundExportDecision
import com.zhiban.rebuild.runtime.provider.OutboundExportDescriptor
import com.zhiban.rebuild.runtime.provider.OutboundExportGate
import com.zhiban.rebuild.runtime.provider.OutboundPurpose
import com.zhiban.rebuild.runtime.provider.OutboundSensitivity
import com.zhiban.rebuild.runtime.provider.ProviderEnvironmentManager
import com.zhiban.rebuild.runtime.runSuspendCatching
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

enum class CloudAsrAvailability { AVAILABLE, CONSENT_REQUIRED, UNSUPPORTED_PROVIDER, PROVIDER_NOT_CONFIGURED }

sealed interface CloudAsrResult {
    data class Success(val text: String) : CloudAsrResult
    data class Failure(val safeCode: String, val retryable: Boolean) : CloudAsrResult
}

/** Agent-owned speech boundary. UI code never knows provider endpoints or credentials. */
interface CloudAsrGateway {
    suspend fun availability(): CloudAsrAvailability
    suspend fun transcribe(audio: File): CloudAsrResult
}

internal interface CloudAsrTransport {
    suspend fun stepFun(credential: ByteArray, audio: File): CloudAsrResult
}

internal class StepFunCloudAsrTransport(private val client: OkHttpClient, private val json: Json = Json { ignoreUnknownKeys = true }) : CloudAsrTransport {
    override suspend fun stepFun(credential: ByteArray, audio: File): CloudAsrResult = withContext(Dispatchers.IO) {
        if (!audio.isFile || audio.length() == 0L) return@withContext CloudAsrResult.Failure("AUDIO_EMPTY", false)
        if (audio.length() > MAX_AUDIO_BYTES) return@withContext CloudAsrResult.Failure("AUDIO_TOO_LARGE", false)
        val audioBytes = audio.readBytes()
        val encoded = try {
            Base64.encodeToString(audioBytes, Base64.NO_WRAP)
        } finally {
            audioBytes.fill(0)
        }
        val body = buildJsonObject {
            putJsonObject("audio") {
                put("data", encoded)
                putJsonObject("input") {
                    putJsonObject("transcription") {
                        put("language", "zh")
                        put("model", "stepaudio-2.5-asr")
                        put("enable_itn", true)
                    }
                    putJsonObject("format") {
                        put("type", "ogg")
                        put("codec", "opus")
                        put("rate", 16_000)
                        put("bits", 16)
                        put("channel", 1)
                    }
                }
            }
        }.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("https://api.stepfun.com/v1/audio/asr/sse")
            .header("Authorization", "Bearer ${credential.decodeToString()}")
            .header("Accept", "text/event-stream")
            .post(body)
            .build()
        runSuspendCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val code = when (response.code) {
                        401, 403 -> "ASR_AUTHENTICATION_FAILED"
                        429 -> "ASR_RATE_LIMITED"
                        in 500..599 -> "ASR_PROVIDER_UNAVAILABLE"
                        else -> "ASR_REQUEST_REJECTED"
                    }
                    return@use CloudAsrResult.Failure(
                        code,
                        response.code == HTTP_TOO_MANY_REQUESTS || response.code >= HTTP_SERVER_ERROR_START,
                    )
                }
                var text = ""
                response.body?.source()?.use { source ->
                    while (!source.exhausted()) {
                        val line = source.readUtf8Line().orEmpty()
                        if (!line.startsWith("data:")) continue
                        val data = line.removePrefix("data:").trim()
                        if (data == "[DONE]") break
                        val event = runSuspendCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
                        if (event["type"]?.jsonPrimitive?.content == "transcript.text.done") {
                            text = event["text"]?.jsonPrimitive?.content.orEmpty()
                        }
                    }
                }
                text = text.trim()
                if (text.isEmpty()) CloudAsrResult.Failure("ASR_EMPTY_RESULT", false) else CloudAsrResult.Success(text)
            }
        }.getOrElse { CloudAsrResult.Failure("ASR_NETWORK_FAILURE", true) }
    }

    private companion object {
        const val MAX_AUDIO_BYTES = 25L * 1024 * 1024
    }
}

private const val HTTP_TOO_MANY_REQUESTS = 429
private const val HTTP_SERVER_ERROR_START = 500

class ProviderCloudAsrGateway internal constructor(
    private val providers: ProviderEnvironmentManager,
    private val credentials: CredentialResolver,
    private val transport: CloudAsrTransport,
    private val outboundGate: OutboundExportGate,
) : CloudAsrGateway {
    override suspend fun availability(): CloudAsrAvailability = when {
        !outboundGate.consentGranted(OutboundChannel.ASR_BATCH) -> CloudAsrAvailability.CONSENT_REQUIRED

        else -> when (providers.activeProfile()?.providerId) {
            null -> CloudAsrAvailability.PROVIDER_NOT_CONFIGURED
            "stepfun" -> CloudAsrAvailability.AVAILABLE
            else -> CloudAsrAvailability.UNSUPPORTED_PROVIDER
        }
    }

    override suspend fun transcribe(audio: File): CloudAsrResult {
        val decision = outboundGate.evaluate(
            OutboundExportDescriptor(
                requestId = "asr-${UUID.randomUUID()}",
                channel = OutboundChannel.ASR_BATCH,
                purpose = OutboundPurpose.USER_AUTHORED,
                sensitivities = setOf(OutboundSensitivity.SENSITIVE),
                payloadCount = 0,
                attachmentCount = 1,
                byteCount = audio.takeIf(File::isFile)?.length() ?: 0,
            ),
        )
        if (decision != OutboundExportDecision.ALLOWED) {
            return CloudAsrResult.Failure("ASR_CLOUD_CONSENT_REQUIRED", false)
        }
        val profile = providers.activeProfile()
            ?: return CloudAsrResult.Failure("ASR_PROVIDER_NOT_CONFIGURED", false)
        if (profile.providerId != "stepfun") return CloudAsrResult.Failure("ASR_PROVIDER_UNSUPPORTED", false)
        return runSuspendCatching {
            credentials.withCredential(profile.credentialRef, profile.keyVersion) { credential ->
                runSuspendCatching { transport.stepFun(credential, audio) }
                    .getOrElse { CloudAsrResult.Failure("ASR_NETWORK_FAILURE", true) }
            }
        }.getOrElse { CloudAsrResult.Failure("ASR_CREDENTIAL_UNAVAILABLE", false) }
            .validated()
    }
}

private fun CloudAsrResult.validated(): CloudAsrResult = when (this) {
    is CloudAsrResult.Failure -> this

    is CloudAsrResult.Success -> text.trim().takeIf(String::isNotEmpty)
        ?.let(CloudAsrResult::Success)
        ?: CloudAsrResult.Failure("ASR_EMPTY_RESULT", false)
}
