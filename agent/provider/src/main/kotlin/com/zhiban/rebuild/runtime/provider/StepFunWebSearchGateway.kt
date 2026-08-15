package com.zhiban.rebuild.runtime.provider

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

data class WebSearchHit(val title: String, val url: String, val snippet: String)

fun interface WebSearchGateway {
    suspend fun search(query: String, limit: Int): List<WebSearchHit>
}

internal fun interface StepFunWebSearchTransport {
    suspend fun search(credential: ByteArray, query: String, limit: Int): List<WebSearchHit>
}

internal class OkHttpStepFunWebSearchTransport(private val client: OkHttpClient, private val json: Json = Json { ignoreUnknownKeys = true }) :
    StepFunWebSearchTransport {
    override suspend fun search(credential: ByteArray, query: String, limit: Int): List<WebSearchHit> {
        val requestBody = buildJsonObject {
            put("query", query)
            put("n", limit)
        }.toString().toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(OFFICIAL_SEARCH_ENDPOINT)
            .header("Authorization", "Bearer ${credential.decodeToString()}")
            .post(requestBody)
            .build()
        return execute(request).use { response ->
            if (!response.isSuccessful) throw searchFailure(response.code)
            parseStepFunSearchResponse(readBoundedBody(response), json, limit)
        }
    }

    private suspend fun execute(request: Request): Response = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, failure: IOException) {
                    if (!continuation.isActive) return
                    continuation.resumeWithException(failure.toProviderFailure())
                }

                override fun onResponse(call: Call, response: Response) {
                    if (continuation.isActive) {
                        continuation.resume(response)
                    } else {
                        response.close()
                    }
                }
            },
        )
    }

    private fun readBoundedBody(response: Response): String {
        val source = response.body?.source() ?: throw ProviderFailure("WEB_SEARCH_RESPONSE_INVALID", false)
        source.request(MAX_RESPONSE_BYTES + 1L)
        if (source.buffer.size > MAX_RESPONSE_BYTES) throw ProviderFailure("WEB_SEARCH_RESPONSE_TOO_LARGE", false)
        return source.readUtf8()
    }

    private companion object {
        const val OFFICIAL_SEARCH_ENDPOINT = "https://api.stepfun.com/v1/search"
        const val MAX_RESPONSE_BYTES = 1_048_576L
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

class StepFunWebSearchGateway internal constructor(
    private val providers: ProviderEnvironmentManager,
    private val credentials: CredentialResolver,
    private val transport: StepFunWebSearchTransport,
    private val outboundGate: OutboundExportGate,
) : WebSearchGateway {
    constructor(
        providers: ProviderEnvironmentManager,
        credentials: CredentialResolver,
        client: OkHttpClient,
        outboundGate: OutboundExportGate,
    ) : this(providers, credentials, OkHttpStepFunWebSearchTransport(client), outboundGate)

    override suspend fun search(query: String, limit: Int): List<WebSearchHit> {
        val normalized = query.trim()
        require(normalized.length in 2..MAX_QUERY_LENGTH && limit in 1..MAX_RESULTS) { "WEB_SEARCH_ARGUMENTS_INVALID" }
        val descriptor = OutboundExportDescriptor(
            requestId = "web-search-${UUID.randomUUID()}",
            channel = OutboundChannel.LLM_INFERENCE,
            purpose = OutboundPurpose.USER_AUTHORED,
            sensitivities = setOf(OutboundSensitivity.PUBLIC),
            payloadCount = 1,
            byteCount = normalized.toByteArray().size.toLong(),
        )
        val decision = outboundGate.evaluate(
            descriptor,
            contentAllowed = !OutboundPiiDetector.containsDirectIdentifier(normalized),
        )
        if (decision != OutboundExportDecision.ALLOWED) throw ProviderFailure("WEB_SEARCH_SENSITIVE_QUERY_BLOCKED", false)
        val profile = providers.activeProfile() ?: throw ProviderFailure("CREDENTIAL_MISSING", false)
        if (profile.providerId != "stepfun") throw ProviderFailure("WEB_SEARCH_PROVIDER_UNSUPPORTED", false)
        return try {
            credentials.withCredential(profile.credentialRef, profile.keyVersion) { credential ->
                transport.search(credential, normalized, limit)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: ProviderFailure) {
            throw failure
        } catch (_: Throwable) {
            throw ProviderFailure("WEB_SEARCH_UNAVAILABLE", true)
        }
    }

    private companion object {
        const val MAX_QUERY_LENGTH = 500
        const val MAX_RESULTS = 5
    }
}

internal fun parseStepFunSearchResponse(body: String, json: Json, limit: Int): List<WebSearchHit> {
    val root = json.parseToJsonElement(body) as? JsonObject ?: throw ProviderFailure("WEB_SEARCH_RESPONSE_INVALID", false)
    val results = root["results"] as? JsonArray ?: throw ProviderFailure("WEB_SEARCH_RESPONSE_INVALID", false)
    return results.mapNotNull { element ->
        val value = element as? JsonObject ?: return@mapNotNull null
        val url = sanitizeWebSourceUrl((value["url"] as? JsonPrimitive)?.contentOrNull) ?: return@mapNotNull null
        val title = sanitizeWebSourceTitle((value["title"] as? JsonPrimitive)?.contentOrNull, url)
        val snippet = listOf("snippet", "content")
            .firstNotNullOfOrNull { key -> (value[key] as? JsonPrimitive)?.contentOrNull?.sanitizeWebSnippet() }
            .orEmpty()
        WebSearchHit(title, url, snippet)
    }.distinctBy(WebSearchHit::url).take(limit)
}

private fun String.sanitizeWebSnippet(): String = replace(Regex("[\\r\\n\\t]+"), " ").trim().take(1_000)

private fun searchFailure(status: Int): ProviderFailure = ProviderFailure(
    code = when (status) {
        401, 403 -> "AUTHENTICATION_FAILED"
        408 -> "TIMEOUT"
        429 -> "RATE_LIMITED"
        in 500..599 -> "PROVIDER_UNAVAILABLE"
        else -> "WEB_SEARCH_REJECTED"
    },
    retryable = status == 408 || status == 429 || status >= 500,
)

private fun IOException.toProviderFailure(): ProviderFailure = ProviderFailure(
    code = when (this) {
        is SocketTimeoutException -> "TIMEOUT"
        is UnknownHostException -> "NETWORK_OFFLINE"
        is SSLPeerUnverifiedException -> "TLS_VERIFICATION_FAILED"
        else -> "WEB_SEARCH_UNAVAILABLE"
    },
    retryable = this !is SSLPeerUnverifiedException,
)
