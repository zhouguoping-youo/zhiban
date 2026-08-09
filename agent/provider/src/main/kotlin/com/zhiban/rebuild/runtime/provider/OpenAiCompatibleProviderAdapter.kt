package com.zhiban.rebuild.runtime.provider

import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class OpenAiCompatibleProviderAdapter(
    private val calls: Call.Factory,
    private val credentials: CredentialResolver,
    private val registry: TrustedProviderRegistry = TrustedProviderRegistry(),
    private val redactor: SecretRedactor = SecretRedactor(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val attachments: ProviderAttachmentResolver = RejectingProviderAttachmentResolver,
) : ProviderAdapter {
    private val json = Json { ignoreUnknownKeys = true }
    private val active = ConcurrentHashMap<String, Call>()

    override suspend fun probe(profile: ProviderProfile): CapabilitySnapshot = probe(profile, "probe-${registry.digest(profile)}")

    override suspend fun probe(profile: ProviderProfile, requestId: String): CapabilitySnapshot = withContext(Dispatchers.IO) {
        val endpoint = registry.resolve(profile)
        val contract = endpoint.modelContracts.getValue(profile.modelId)
        val call = credentials.withCredential(profile.credentialRef, profile.keyVersion) { secret ->
            calls.newCall(
                Request.Builder().url(endpoint.probeUrl)
                    .header("Authorization", bearer(secret)).get().build(),
            )
        }
        check(active.putIfAbsent(requestId, call) == null) { "REQUEST_ALREADY_ACTIVE" }
        val body: String
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw mapResponseFailure(response)
                val responseBody = response.body
                if (responseBody != null && responseBody.contentLength() > MAX_MODELS_RESPONSE_BYTES) {
                    call.cancel()
                    throw ProviderFailure("PROVIDER_RESPONSE_TOO_LARGE", retryable = false)
                }
                val source = responseBody?.source()
                source?.request(MAX_MODELS_RESPONSE_BYTES + 1L)
                val bytes = source?.readByteArray(source.buffer.size) ?: ByteArray(0)
                if (bytes.size > MAX_MODELS_RESPONSE_BYTES) {
                    bytes.fill(0)
                    call.cancel()
                    throw ProviderFailure("PROVIDER_RESPONSE_TOO_LARGE", retryable = false)
                }
                body = try {
                    bytes.decodeToString()
                } finally {
                    bytes.fill(0)
                }
            }
        } finally {
            active.remove(requestId, call)
        }
        val availableModels = parseAvailableModels(body)
        if (profile.modelId !in availableModels) throw ProviderFailure("MODEL_NOT_AVAILABLE", retryable = false)
        val now = clock()
        CapabilitySnapshot(
            registry.digest(profile),
            contract.modalities,
            contract.features,
            contract.maxContextTokens,
            contract.maxOutputTokens,
            now,
            now + 15 * 60_000,
        )
    }

    override fun stream(request: ModelRequest): Flow<ModelEvent> = flow {
        val endpoint = registry.resolve(request.profile)
        request.capability.requireFresh(clock(), registry.digest(request.profile))
        check(request.maxTokens in 1..request.capability.maxOutputTokens) { "OUTPUT_TOKEN_LIMIT_EXCEEDED" }
        check(
            inputTokenUpperBound(request.messages) + request.maxTokens <= request.capability.maxContextTokens.toLong(),
        ) {
            "CONTEXT_TOKEN_LIMIT_EXCEEDED"
        }
        if (request.jsonSchema != null) check("json_schema" in request.capability.features) { "SCHEMA_UNSUPPORTED" }
        if (request.toolsJson != null) check("tools" in request.capability.features) { "TOOLS_UNSUPPORTED" }
        val body = buildBody(request, endpoint)
        val call = credentials.withCredential(request.profile.credentialRef, request.profile.keyVersion) { secret ->
            calls.newCall(
                Request.Builder().url(endpoint.chatUrl)
                    .header("Authorization", bearer(secret))
                    .header("Content-Type", "application/json")
                    .post(body.toRequestBody("application/json".toMediaType())).build(),
            )
        }
        check(active.putIfAbsent(request.requestId, call) == null) { "REQUEST_ALREADY_ACTIVE" }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    throw mapResponseFailure(response)
                }
                var ordinal = 0L
                val pendingTools = linkedMapOf<Int, PendingToolCall>()
                val toolIdIndexes = mutableMapOf<String, Int>()
                var usageEmitted = false
                var finalEmitted = false
                var totalResponseBytes = 0L
                val reasoningFilter = ReasoningTagFilter()
                response.body?.source()?.use { source ->
                    while (!source.exhausted()) {
                        val line = try {
                            source.readUtf8LineStrict(MAX_SSE_FRAME_BYTES)
                        } catch (_: java.io.EOFException) {
                            call.cancel()
                            throw ProviderFailure("PROVIDER_FRAME_TOO_LARGE", retryable = false)
                        }
                        totalResponseBytes += line.toByteArray(Charsets.UTF_8).size + 1L
                        if (totalResponseBytes > MAX_STREAM_RESPONSE_BYTES) {
                            call.cancel()
                            throw ProviderFailure("PROVIDER_RESPONSE_TOO_LARGE", retryable = false)
                        }
                        if (!line.startsWith("data:")) continue
                        val data = line.removePrefix("data:").trim()
                        if (data == "[DONE]") break
                        val chunk = runCatching { json.parseToJsonElement(data) as? JsonObject }.getOrNull() ?: continue
                        val choice = (chunk["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
                        val delta = choice?.get("delta") as? JsonObject
                        (delta?.get("content") as? JsonPrimitive)?.content
                            ?.takeIf { it.isNotEmpty() }
                            ?.let(reasoningFilter::accept)
                            ?.takeIf { it.isNotEmpty() }
                            ?.let { emit(ModelEvent.Delta(ordinal++, it)) }
                        val toolCalls = delta?.get("tool_calls") as? JsonArray
                        toolCalls?.forEach { item ->
                            val objectItem = item as? JsonObject ?: return@forEach
                            val index =
                                (objectItem["index"] as? JsonPrimitive)?.content?.toIntOrNull() ?: return@forEach
                            if (index < 0 ||
                                index >= MAX_TOOL_CALLS
                            ) {
                                throw ProviderFailure("INVALID_TOOL_CALL_INDEX", retryable = false)
                            }
                            val fn = objectItem["function"] as? JsonObject ?: return@forEach
                            val pending = pendingTools.getOrPut(index) { PendingToolCall(index) }
                            (objectItem["id"] as? JsonPrimitive)?.content?.takeIf(
                                String::isNotEmpty,
                            )?.let { providerCallId ->
                                if (!SAFE_PROVIDER_CALL_ID.matches(
                                        providerCallId,
                                    )
                                ) {
                                    throw ProviderFailure("INVALID_TOOL_CALL_ID", retryable = false)
                                }
                                if (pending.providerCallId != null && pending.providerCallId != providerCallId) {
                                    throw ProviderFailure("TOOL_CALL_ID_CONFLICT", retryable = false)
                                }
                                val previousIndex = toolIdIndexes.putIfAbsent(providerCallId, index)
                                if (previousIndex != null && previousIndex != index) {
                                    throw ProviderFailure("TOOL_CALL_ID_CONFLICT", retryable = false)
                                }
                                pending.providerCallId = providerCallId
                            }
                            (fn["name"] as? JsonPrimitive)?.content?.takeIf(String::isNotEmpty)?.let { name ->
                                if (pending.name != null &&
                                    pending.name != name
                                ) {
                                    throw ProviderFailure("TOOL_CALL_NAME_CONFLICT", retryable = false)
                                }
                                pending.name = name
                            }
                            (fn["arguments"] as? JsonPrimitive)?.content?.let { fragment ->
                                if (pending.arguments.length + fragment.length > MAX_TOOL_ARGUMENT_CHARS) {
                                    throw ProviderFailure("TOOL_ARGUMENTS_TOO_LARGE", retryable = false)
                                }
                                pending.arguments.append(fragment)
                            }
                        }
                        val usage = chunk["usage"] as? JsonObject
                        if (usage != null && !usageEmitted) {
                            emit(ModelEvent.Usage(usage.int("prompt_tokens"), usage.int("completion_tokens")))
                            usageEmitted = true
                        }
                        (choice?.get("finish_reason") as? JsonPrimitive)?.content?.let { finishReason ->
                            if (finalEmitted) return@let
                            reasoningFilter.finish().takeIf { it.isNotEmpty() }?.let {
                                emit(ModelEvent.Delta(ordinal++, it))
                            }
                            // StepFun may finish a valid tool call with "stop" rather than
                            // OpenAI's "tool_calls". Pending structured calls are authoritative;
                            // never discard them solely because the provider uses that finish reason.
                            if (pendingTools.isNotEmpty()) {
                                pendingTools.toSortedMap().values.forEach { pending ->
                                    val name =
                                        pending.name ?: throw ProviderFailure("INVALID_TOOL_CALL", retryable = false)
                                    val args = pending.arguments.toString()
                                    runCatching {
                                        json.parseToJsonElement(args)
                                    }.getOrElse { throw ProviderFailure("INVALID_TOOL_ARGUMENTS", retryable = false) }
                                    val providerCallId =
                                        pending.providerCallId ?: canonicalToolCallId(request.requestId, pending.index)
                                    emit(ModelEvent.ToolCall(ordinal++, providerCallId, name, args))
                                }
                                pendingTools.clear()
                            }
                            emit(ModelEvent.Final(finishReason))
                            finalEmitted = true
                        }
                    }
                }
            }
        } finally {
            active.remove(request.requestId, call)
        }
    }.flowOn(Dispatchers.IO)

    override fun cancel(requestId: String): Boolean = active.remove(requestId)?.let {
        it.cancel()
        true
    } ?: false

    private fun buildBody(request: ModelRequest, endpoint: TrustedProviderEndpoint): String = buildJsonObject {
        put("model", request.profile.modelId)
        put("stream", true)
        put(endpoint.maxTokensField, request.maxTokens)
        putJsonArray("messages") {
            request.messages.forEachIndexed { index, message ->
                add(
                    buildJsonObject {
                        put("role", message.role)
                        if (index == request.messages.lastIndex && message.role == "user" &&
                            request.attachments.isNotEmpty()
                        ) {
                            putJsonArray("content") {
                                add(
                                    buildJsonObject {
                                        put("type", "text")
                                        put("text", message.content)
                                    },
                                )
                                request.attachments.forEach { attachment ->
                                    attachments.imageDataUrls(attachment, clock()).forEach { dataUrl ->
                                        add(
                                            buildJsonObject {
                                                put("type", "image_url")
                                                put("image_url", buildJsonObject { put("url", dataUrl) })
                                            },
                                        )
                                    }
                                }
                            }
                        } else {
                            put("content", message.content)
                        }
                    },
                )
            }
        }
        request.jsonSchema?.let { schema ->
            put(
                "response_format",
                buildJsonObject {
                    put("type", "json_schema")
                    put("json_schema", json.parseToJsonElement(schema))
                },
            )
        }
        request.toolsJson?.let { tools ->
            put("tools", json.parseToJsonElement(tools))
            val forcedName = request.forcedToolName
            if (forcedName != null) {
                put(
                    "tool_choice",
                    buildJsonObject {
                        put("type", "function")
                        put("function", buildJsonObject { put("name", forcedName) })
                    },
                )
            } else {
                put("tool_choice", "auto")
            }
        }
    }.toString()

    private fun mapFailure(status: Int, retryAfter: String?, requestId: String?): ProviderFailure = ProviderFailure(
        code = when (status) {
            401, 403 -> "AUTHENTICATION_FAILED"
            408 -> "TIMEOUT"
            429 -> "RATE_LIMITED"
            in 500..599 -> "PROVIDER_UNAVAILABLE"
            else -> "PROVIDER_REJECTED"
        },
        retryable = status == 408 || status == 429 || status >= 500,
        retryAfterMillis = retryAfter?.toLongOrNull()?.times(1000),
        safeRequestId = redactor.safeRequestId(requestId),
    )

    private fun mapResponseFailure(response: okhttp3.Response): ProviderFailure {
        val body = runCatching { response.peekBody(MAX_ERROR_RESPONSE_BYTES).string() }.getOrDefault("")
        val bodyRequestId = runCatching {
            val root = json.parseToJsonElement(body) as JsonObject
            val error = root["error"] as? JsonObject
            (error?.get("request_id") as? JsonPrimitive)?.content
                ?: (root["request_id"] as? JsonPrimitive)?.content
        }.getOrNull()
        val safeId = redactor.safeRequestId(
            response.header("trace_id") ?: response.header("x-request-id") ?: bodyRequestId,
        )
        return standardBusinessFailure(body, safeId, response.header("Retry-After"))
            ?: mapFailure(response.code, response.header("Retry-After"), safeId)
    }

    private fun standardBusinessFailure(body: String, safeRequestId: String?, retryAfter: String?): ProviderFailure? = runCatching {
        val root = json.parseToJsonElement(body) as JsonObject
        val error = root["error"] as? JsonObject ?: return@runCatching null
        val providerCode = listOf("code", "type").mapNotNull { key ->
            (error[key] as? JsonPrimitive)?.content
        }.joinToString(" ").lowercase()
        if (providerCode.isBlank()) return@runCatching null
        val code = when {
            listOf(
                "invalid_api_key",
                "invalidapikey",
                "authentication",
                "unauthorized",
                "401002",
                "signature",
            ).any(providerCode::contains) -> "AUTHENTICATION_FAILED"

            listOf("throttl", "rate_limit", "too_many", "429").any(providerCode::contains) -> "RATE_LIMITED"

            listOf(
                "arrearage",
                "overdue",
                "insufficient",
                "quota",
                "balance",
            ).any(providerCode::contains) -> "INSUFFICIENT_QUOTA"

            listOf(
                "input_too_long",
                "context_length",
                "token_limit",
                "400003",
            ).any(providerCode::contains) -> "CONTEXT_TOKEN_LIMIT_EXCEEDED"

            listOf("inspection", "sensitive", "content_filter").any(providerCode::contains) -> "INPUT_SENSITIVE"

            listOf(
                "invalid_request",
                "invalid_parameter",
                "400001",
                "400002",
                "unsupported",
            ).any(providerCode::contains) -> "INVALID_REQUEST"

            listOf("internal", "upstream", "unavailable").any(providerCode::contains) -> "PROVIDER_UNAVAILABLE"

            else -> return@runCatching null
        }
        ProviderFailure(
            code = code,
            retryable = code == "RATE_LIMITED" || code == "PROVIDER_UNAVAILABLE",
            retryAfterMillis = retryAfter?.toLongOrNull()?.times(1_000),
            safeRequestId = safeRequestId,
        )
    }.getOrNull()

    private fun JsonObject.int(key: String): Int = (this[key] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0

    private fun parseAvailableModels(body: String): Set<String> = runCatching {
        val root = json.parseToJsonElement(body) as JsonObject
        (root["data"] as? JsonArray).orEmpty().mapNotNull { item ->
            ((item as? JsonObject)?.get("id") as? JsonPrimitive)?.content
        }.toSet()
    }.getOrDefault(emptySet())

    private fun inputTokenUpperBound(messages: List<ModelMessage>): Long = messages.sumOf { message ->
        message.content.toByteArray(Charsets.UTF_8).size.toLong() + MESSAGE_TOKEN_OVERHEAD
    }

    private fun bearer(secret: ByteArray): String = "Bearer ${secret.decodeToString()}"

    private fun canonicalToolCallId(requestId: String, index: Int): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$requestId|$index".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "canonical_$digest"
    }

    private class PendingToolCall(val index: Int) {
        var providerCallId: String? = null
        var name: String? = null
        val arguments = StringBuilder()
    }

    private class ReasoningTagFilter {
        private enum class Mode { UNDECIDED, SUPPRESSING, VISIBLE }

        private var mode = Mode.UNDECIDED
        private val pending = StringBuilder()

        fun accept(fragment: String): String = when (mode) {
            Mode.VISIBLE -> fragment
            Mode.UNDECIDED -> decide(fragment)
            Mode.SUPPRESSING -> suppress(fragment)
        }

        fun finish(): String = when (mode) {
            Mode.UNDECIDED -> pending.toString().also {
                pending.clear()
                mode = Mode.VISIBLE
            }

            Mode.SUPPRESSING, Mode.VISIBLE -> ""
        }

        private fun decide(fragment: String): String {
            pending.append(fragment)
            val candidate = pending.toString().trimStart()
            return when {
                OPEN_TAG.startsWith(candidate) -> ""

                candidate.startsWith(OPEN_TAG) -> {
                    val afterOpen = candidate.removePrefix(OPEN_TAG)
                    pending.clear()
                    mode = Mode.SUPPRESSING
                    suppress(afterOpen)
                }

                else -> pending.toString().also {
                    pending.clear()
                    mode = Mode.VISIBLE
                }
            }
        }

        private fun suppress(fragment: String): String {
            pending.append(fragment)
            val closingIndex = pending.indexOf(CLOSE_TAG)
            if (closingIndex < 0) {
                if (pending.length > CLOSE_TAG.length - 1) {
                    pending.delete(0, pending.length - (CLOSE_TAG.length - 1))
                }
                return ""
            }
            val visible = pending.substring(closingIndex + CLOSE_TAG.length)
            pending.clear()
            mode = Mode.VISIBLE
            return visible
        }

        private companion object {
            const val OPEN_TAG = "<think>"
            const val CLOSE_TAG = "</think>"
        }
    }

    private companion object {
        const val MAX_TOOL_ARGUMENT_CHARS = 65_536
        const val MAX_TOOL_CALLS = 64
        const val MAX_MODELS_RESPONSE_BYTES = 262_144
        const val MAX_SSE_FRAME_BYTES = 65_536L
        const val MAX_STREAM_RESPONSE_BYTES = 4L * 1024 * 1024
        const val MAX_ERROR_RESPONSE_BYTES = 65_536L
        const val MESSAGE_TOKEN_OVERHEAD = 16L
        val SAFE_PROVIDER_CALL_ID = Regex("[A-Za-z0-9._:-]{1,128}")
    }
}
