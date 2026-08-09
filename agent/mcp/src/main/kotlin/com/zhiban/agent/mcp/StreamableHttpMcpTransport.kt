package com.zhiban.agent.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class StreamableHttpMcpTransport(
    endpoint: String,
    private val client: OkHttpClient,
    private val bearerCredential: suspend () -> ByteArray? = { null },
    private val maxResponseBytes: Long = 1_048_576,
) : McpTransport {
    private val url = requireNotNull(endpoint.toHttpUrlOrNull()) { "MCP_ENDPOINT_INVALID" }.also {
        val local = it.host in setOf("localhost", "127.0.0.1", "10.0.2.2", "::1")
        require(it.isHttps || local) { "MCP_ENDPOINT_REQUIRES_HTTPS" }
        require(it.username.isEmpty() && it.password.isEmpty()) { "MCP_ENDPOINT_EMBEDDED_CREDENTIALS" }
    }

    @Volatile private var sessionId: String? = null

    override suspend fun exchange(message: JsonObject): JsonObject? = withContext(Dispatchers.IO) {
        val credential = bearerCredential()
        try {
            val request = Request.Builder().url(url)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", McpClient.PROTOCOL_VERSION)
                .apply {
                    sessionId?.let { header("Mcp-Session-Id", it) }
                    credential?.takeIf {
                        it.isNotEmpty()
                    }?.let { header("Authorization", "Bearer ${it.toString(Charsets.UTF_8)}") }
                }
                .post(message.toString().toRequestBody(JSON_MEDIA_TYPE)).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw McpProtocolException("MCP_HTTP_${response.code}")
                response.header("Mcp-Session-Id")?.takeIf { it.length in 1..256 }?.let { sessionId = it }
                val requestId = message["id"]?.jsonPrimitive?.content
                if (requestId == null && response.code in ACCEPTED_NOTIFICATION_STATUSES) return@use null
                val body = response.body ?: throw McpProtocolException("MCP_EMPTY_RESPONSE")
                if (body.contentLength() > maxResponseBytes) throw McpProtocolException("MCP_RESPONSE_TOO_LARGE")
                val source = body.source()
                source.request(maxResponseBytes + 1)
                if (source.buffer.size > maxResponseBytes) throw McpProtocolException("MCP_RESPONSE_TOO_LARGE")
                val bytes = source.readByteArray()
                val text = try {
                    bytes.toString(Charsets.UTF_8)
                } finally {
                    bytes.fill(0)
                }
                when {
                    response.header(
                        "Content-Type",
                    ).orEmpty().startsWith("text/event-stream") -> parseEventStream(text, requestId)

                    else -> parseJsonObject(text)
                }
            }
        } finally {
            credential?.fill(0)
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        internal fun parseEventStream(text: String, requestId: String?): JsonObject? {
            val candidates = sseDataEvents(text)
                .mapNotNull { runCatching { parseJsonObject(it) }.getOrNull() }
            return if (requestId ==
                null
            ) {
                null
            } else {
                candidates.firstOrNull { it["id"]?.jsonPrimitive?.content == requestId }
                    ?: throw McpProtocolException("MCP_INVALID_RESPONSE")
            }
        }

        internal fun sseDataEvents(text: String): List<String> {
            val events = mutableListOf<String>()
            val data = mutableListOf<String>()
            fun finishEvent() {
                if (data.isNotEmpty()) events += data.joinToString("\n")
                data.clear()
            }
            text.lineSequence().forEach { rawLine ->
                val line = rawLine.removeSuffix("\r")
                if (line.isEmpty()) {
                    finishEvent()
                } else if (!line.startsWith(":")) {
                    val separator = line.indexOf(':')
                    val field = if (separator < 0) line else line.substring(0, separator)
                    val rawValue = if (separator < 0) "" else line.substring(separator + 1)
                    val value = rawValue.removePrefix(" ")
                    if (field == "data") data += value
                    // event, id and retry are valid metadata but do not alter JSON-RPC payload matching.
                }
            }
            finishEvent()
            return events
        }
        internal fun parseJsonObject(text: String): JsonObject = runCatching { Json.parseToJsonElement(text).jsonObject }
            .getOrElse { throw McpProtocolException("MCP_INVALID_RESPONSE") }
    }
}

private val ACCEPTED_NOTIFICATION_STATUSES = setOf(202, 204)
