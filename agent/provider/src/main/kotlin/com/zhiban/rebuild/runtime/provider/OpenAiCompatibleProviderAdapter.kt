package com.zhiban.rebuild.runtime.provider

import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLPeerUnverifiedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource

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
        } catch (_: SSLPeerUnverifiedException) {
            throw ProviderFailure("TLS_VERIFICATION_FAILED", retryable = false)
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
        validateStreamRequest(request)
        val call = createStreamingCall(request, endpoint)
        check(active.putIfAbsent(request.requestId, call) == null) { "REQUEST_ALREADY_ACTIVE" }
        try {
            emitStreamingResponse(call, request.requestId)
        } catch (_: SSLPeerUnverifiedException) {
            throw ProviderFailure("TLS_VERIFICATION_FAILED", retryable = false)
        } finally {
            active.remove(request.requestId, call)
        }
    }.flowOn(Dispatchers.IO)

    private fun validateStreamRequest(request: ModelRequest) {
        request.capability.requireFresh(clock(), registry.digest(request.profile))
        check(request.maxTokens in 1..request.capability.maxOutputTokens) { "OUTPUT_TOKEN_LIMIT_EXCEEDED" }
        check(
            inputTokenUpperBound(request.messages) + request.maxTokens <= request.capability.maxContextTokens.toLong(),
        ) {
            "CONTEXT_TOKEN_LIMIT_EXCEEDED"
        }
        if (request.jsonSchema != null) check("json_schema" in request.capability.features) { "SCHEMA_UNSUPPORTED" }
        if (request.toolsJson != null) check("tools" in request.capability.features) { "TOOLS_UNSUPPORTED" }
    }

    private suspend fun createStreamingCall(request: ModelRequest, endpoint: TrustedProviderEndpoint): Call {
        val body = buildBody(request, endpoint)
        return credentials.withCredential(request.profile.credentialRef, request.profile.keyVersion) { secret ->
            calls.newCall(
                Request.Builder().url(endpoint.chatUrl)
                    .header("Authorization", bearer(secret))
                    .header("Content-Type", "application/json")
                    .post(body.toRequestBody("application/json".toMediaType())).build(),
            )
        }
    }

    private suspend fun FlowCollector<ModelEvent>.emitStreamingResponse(call: Call, requestId: String) {
        call.execute().use { response ->
            if (!response.isSuccessful) throw mapResponseFailure(response)
            emitSuccessfulStream(response.body, call, requestId)
        }
    }

    private suspend fun FlowCollector<ModelEvent>.emitSuccessfulStream(body: okhttp3.ResponseBody?, call: Call, requestId: String) {
        val source = body?.source() ?: throw ProviderFailure("PROVIDER_PROTOCOL_ERROR", retryable = true)
        val decoder = StreamDecoder(requestId)
        try {
            source.use { emitDecodedStream(source, call, decoder) }
        } catch (failure: ProviderTransportException) {
            val recoveredEvents = decoder.finishAfterTransportFailure()
                ?: throw failure.ioFailure
            recoveredEvents.forEach { emit(it) }
            return
        }
        decoder.finishAtCleanEof().forEach { emit(it) }
    }

    private suspend fun FlowCollector<ModelEvent>.emitDecodedStream(source: BufferedSource, call: Call, decoder: StreamDecoder) {
        while (!readSourceExhausted(source)) {
            val data = try {
                decoder.readData(source, call)
            } catch (failure: IOException) {
                throw ProviderTransportException(failure)
            } ?: continue
            val decoded = decoder.accept(data)
            decoded.events.forEach { emit(it) }
            if (decoded.isDone) break
        }
    }

    private fun readSourceExhausted(source: BufferedSource): Boolean = try {
        source.exhausted()
    } catch (failure: IOException) {
        throw ProviderTransportException(failure)
    }

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
            (error?.get("request_id") as? JsonPrimitive)?.contentOrNull
                ?: (root["request_id"] as? JsonPrimitive)?.contentOrNull
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
            (error[key] as? JsonPrimitive)?.contentOrNull
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

    private fun JsonObject.int(key: String): Int = (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0

    private fun parseAvailableModels(body: String): Set<String> = runCatching {
        val root = json.parseToJsonElement(body) as JsonObject
        (root["data"] as? JsonArray).orEmpty().mapNotNull { item ->
            ((item as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull
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

    private class ProviderTransportException(val ioFailure: IOException) : RuntimeException(ioFailure)

    private inner class StreamDecoder(private val requestId: String) {
        private val pendingTools = linkedMapOf<Int, PendingToolCall>()
        private val toolIdIndexes = mutableMapOf<String, Int>()
        private val reasoningFilter = ReasoningTagFilter()
        private var usageEmitted = false
        private var finalEmitted = false
        private var totalResponseBytes = 0L
        private var ordinal = 0L

        fun readData(source: BufferedSource, call: Call): String? {
            val newlineOffset = source.indexOf('\n'.code.toByte(), 0L, MAX_SSE_FRAME_BYTES + 1L)
            val line = when {
                newlineOffset >= 0L -> source.readUtf8LineStrict(MAX_SSE_FRAME_BYTES)

                source.buffer.size <= MAX_SSE_FRAME_BYTES -> source.readUtf8()

                else -> {
                    call.cancel()
                    throw ProviderFailure("PROVIDER_FRAME_TOO_LARGE", retryable = false)
                }
            }
            if (line.toByteArray(Charsets.UTF_8).size > MAX_SSE_FRAME_BYTES) {
                call.cancel()
                throw ProviderFailure("PROVIDER_FRAME_TOO_LARGE", retryable = false)
            }
            totalResponseBytes += line.toByteArray(Charsets.UTF_8).size + 1L
            if (totalResponseBytes > MAX_STREAM_RESPONSE_BYTES) {
                call.cancel()
                throw ProviderFailure("PROVIDER_RESPONSE_TOO_LARGE", retryable = false)
            }
            return line.takeIf { it.startsWith("data:") }
                ?.removePrefix("data:")
                ?.trim()
                ?.takeIf(String::isNotEmpty)
        }

        fun accept(data: String): DecodedStreamChunk {
            if (data == "[DONE]") {
                val events = if (finalEmitted) {
                    emptyList()
                } else {
                    finalize(if (pendingTools.isEmpty()) "stop" else "tool_calls")
                }
                return DecodedStreamChunk(events, isDone = true)
            }
            val chunk = parseChunk(data)
            streamFailure(chunk)?.let { throw it }
            val choice = (chunk["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
            val delta = choice?.get("delta") as? JsonObject
            val events = mutableListOf<ModelEvent>()
            contentEvent(delta)?.let(events::add)
            accumulateTools(delta)
            usageEvent(chunk)?.let(events::add)
            (choice?.get("finish_reason") as? JsonPrimitive)?.contentOrNull?.let { reason ->
                if (!finalEmitted) events += finalize(reason)
            }
            return DecodedStreamChunk(events, isDone = false)
        }

        fun finishAtCleanEof(): List<ModelEvent> = if (finalEmitted) {
            emptyList()
        } else {
            finalize(if (pendingTools.isEmpty()) "stop" else "tool_calls")
        }

        fun finishAfterTransportFailure(): List<ModelEvent>? = when {
            finalEmitted -> emptyList()
            pendingTools.isNotEmpty() -> finalize("tool_calls")
            else -> null
        }

        private fun parseChunk(data: String): JsonObject = try {
            json.parseToJsonElement(data) as? JsonObject
                ?: throw ProviderFailure("PROVIDER_PROTOCOL_ERROR", retryable = true)
        } catch (failure: ProviderFailure) {
            throw failure
        } catch (_: Throwable) {
            throw ProviderFailure("PROVIDER_PROTOCOL_ERROR", retryable = true)
        }

        private fun streamFailure(chunk: JsonObject): ProviderFailure? {
            val error = chunk["error"] as? JsonObject ?: return null
            val safeRequestId = redactor.safeRequestId(
                (error["request_id"] as? JsonPrimitive)?.contentOrNull
                    ?: (chunk["request_id"] as? JsonPrimitive)?.contentOrNull,
            )
            return standardBusinessFailure(chunk.toString(), safeRequestId, retryAfter = null)
                ?: ProviderFailure("PROVIDER_REJECTED", retryable = false, safeRequestId = safeRequestId)
        }

        private fun contentEvent(delta: JsonObject?): ModelEvent.Delta? = (delta?.get("content") as? JsonPrimitive)?.contentOrNull
            ?.takeIf(String::isNotEmpty)
            ?.let(reasoningFilter::accept)
            ?.takeIf(String::isNotEmpty)
            ?.let { ModelEvent.Delta(ordinal++, it) }

        private fun accumulateTools(delta: JsonObject?) {
            val calls = delta?.get("tool_calls") as? JsonArray ?: return
            calls.forEach { item -> accumulateTool(item as? JsonObject ?: return@forEach) }
        }

        private fun accumulateTool(item: JsonObject) {
            val index = (item["index"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: return
            if (index !in 0 until MAX_TOOL_CALLS) {
                throw ProviderFailure("INVALID_TOOL_CALL_INDEX", retryable = false)
            }
            val function = item["function"] as? JsonObject ?: return
            val pending = pendingTools.getOrPut(index) { PendingToolCall(index) }
            (item["id"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotEmpty)?.let { id ->
                validateToolId(id, index, pending)
                pending.providerCallId = id
            }
            (function["name"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotEmpty)?.let { name ->
                if (pending.name != null && pending.name != name) {
                    throw ProviderFailure("TOOL_CALL_NAME_CONFLICT", retryable = false)
                }
                pending.name = name
            }
            (function["arguments"] as? JsonPrimitive)?.contentOrNull?.let { fragment ->
                if (pending.arguments.length + fragment.length > MAX_TOOL_ARGUMENT_CHARS) {
                    throw ProviderFailure("TOOL_ARGUMENTS_TOO_LARGE", retryable = false)
                }
                pending.arguments.append(fragment)
            }
        }

        private fun validateToolId(id: String, index: Int, pending: PendingToolCall) {
            if (!SAFE_PROVIDER_CALL_ID.matches(id)) {
                throw ProviderFailure("INVALID_TOOL_CALL_ID", retryable = false)
            }
            if (pending.providerCallId != null && pending.providerCallId != id) {
                throw ProviderFailure("TOOL_CALL_ID_CONFLICT", retryable = false)
            }
            val previousIndex = toolIdIndexes.putIfAbsent(id, index)
            if (previousIndex != null && previousIndex != index) {
                throw ProviderFailure("TOOL_CALL_ID_CONFLICT", retryable = false)
            }
        }

        private fun usageEvent(chunk: JsonObject): ModelEvent.Usage? {
            val usage = chunk["usage"] as? JsonObject ?: return null
            if (usageEmitted) return null
            usageEmitted = true
            return ModelEvent.Usage(usage.int("prompt_tokens"), usage.int("completion_tokens"))
        }

        private fun finalize(finishReason: String): List<ModelEvent> = buildList {
            reasoningFilter.finish().takeIf(String::isNotEmpty)?.let {
                add(ModelEvent.Delta(ordinal++, it))
            }
            // Pending structured calls are authoritative even when StepFun reports "stop".
            pendingTools.toSortedMap().values.forEach { pending -> add(finalizeTool(pending)) }
            pendingTools.clear()
            add(ModelEvent.Final(finishReason))
            finalEmitted = true
        }

        private fun finalizeTool(pending: PendingToolCall): ModelEvent.ToolCall {
            val name = pending.name ?: throw ProviderFailure("INVALID_TOOL_CALL", retryable = false)
            val arguments = pending.arguments.toString()
            runCatching { json.parseToJsonElement(arguments) }
                .getOrElse { throw ProviderFailure("INVALID_TOOL_ARGUMENTS", retryable = false) }
            val id = pending.providerCallId ?: canonicalToolCallId(requestId, pending.index)
            return ModelEvent.ToolCall(ordinal++, id, name, arguments)
        }
    }

    private data class DecodedStreamChunk(val events: List<ModelEvent>, val isDone: Boolean)

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
