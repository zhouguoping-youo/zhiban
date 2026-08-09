package com.zhiban.agent.mcp

import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

fun interface McpTransport {
    /** Returns null for notifications, a JSON-RPC response for requests. */
    suspend fun exchange(message: JsonObject): JsonObject?
}

data class McpServerInfo(val name: String, val version: String)
data class McpToolDescriptor(val name: String, val description: String?, val inputSchema: JsonObject)
data class McpToolResult(val content: JsonArray, val isError: Boolean)
data class McpInitialization(val protocolVersion: String, val serverInfo: McpServerInfo)

class McpProtocolException(val safeCode: String) : RuntimeException(safeCode)

/** MCP JSON-RPC 2.0 client core; transport/auth remain adapters outside this module. */
class McpClient(private val transport: McpTransport, private val clientName: String = "zhiban-agent", private val clientVersion: String = "1.0") {
    private val ids = AtomicLong()
    private var initialized = false

    suspend fun initialize(): McpInitialization {
        val result = request(
            "initialize",
            buildJsonObject {
                put("protocolVersion", PROTOCOL_VERSION)
                put("capabilities", buildJsonObject {})
                put(
                    "clientInfo",
                    buildJsonObject {
                        put("name", clientName)
                        put("version", clientVersion)
                    },
                )
            },
        )
        val version = requiredString(result, "protocolVersion")
        if (version !in SUPPORTED_PROTOCOL_VERSIONS) throw McpProtocolException("MCP_PROTOCOL_UNSUPPORTED")
        val info = result["serverInfo"]?.jsonObject ?: throw McpProtocolException("MCP_INVALID_RESPONSE")
        transport.exchange(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("method", "notifications/initialized")
            },
        )
        initialized = true
        return McpInitialization(version, McpServerInfo(requiredString(info, "name"), requiredString(info, "version")))
    }

    suspend fun listTools(): List<McpToolDescriptor> {
        requireInitialized()
        val all = mutableListOf<McpToolDescriptor>()
        var cursor: String? = null
        repeat(MAX_TOOL_PAGES) {
            val result = request("tools/list", buildJsonObject { cursor?.let { put("cursor", it) } })
            all += (result["tools"] as? JsonArray ?: throw McpProtocolException("MCP_INVALID_RESPONSE")).map { item ->
                val tool = item.jsonObject
                val name = requiredString(tool, "name").also { validateToolName(it) }
                McpToolDescriptor(
                    name,
                    tool["description"]?.jsonPrimitive?.content,
                    tool["inputSchema"]?.jsonObject ?: JsonObject(emptyMap()),
                )
            }
            cursor = result["nextCursor"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)
            if (cursor == null) {
                return all.also { tools ->
                    if (tools.map { it.name }.distinct().size !=
                        tools.size
                    ) {
                        throw McpProtocolException("MCP_DUPLICATE_TOOL")
                    }
                }
            }
        }
        throw McpProtocolException("MCP_PAGINATION_LIMIT")
    }

    suspend fun callTool(name: String, arguments: JsonObject): McpToolResult {
        requireInitialized()
        validateToolName(name)
        val result = request(
            "tools/call",
            buildJsonObject {
                put("name", name)
                put("arguments", arguments)
            },
        )
        val content = result["content"] as? JsonArray ?: throw McpProtocolException("MCP_INVALID_RESPONSE")
        val isError = (result["isError"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false
        return McpToolResult(content, isError)
    }

    private suspend fun request(method: String, params: JsonObject): JsonObject {
        val id = ids.incrementAndGet().toString()
        val response = transport.exchange(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", method)
                put("params", params)
            },
        ) ?: throw McpProtocolException("MCP_INVALID_RESPONSE")
        if (response["jsonrpc"]?.jsonPrimitive?.content != "2.0" || response["id"]?.jsonPrimitive?.content != id) {
            throw McpProtocolException("MCP_INVALID_RESPONSE")
        }
        response["error"]?.jsonObject?.let { throw McpProtocolException("MCP_REMOTE_ERROR") }
        return response["result"]?.jsonObject ?: throw McpProtocolException("MCP_INVALID_RESPONSE")
    }

    private fun requireInitialized() {
        if (!initialized) throw McpProtocolException("MCP_NOT_INITIALIZED")
    }
    private fun requiredString(value: JsonObject, key: String) = value[key]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)
        ?: throw McpProtocolException("MCP_INVALID_RESPONSE")
    private fun validateToolName(name: String) {
        if (!TOOL_NAME.matches(name)) throw McpProtocolException("MCP_INVALID_TOOL_NAME")
    }

    companion object {
        const val PROTOCOL_VERSION = "2025-06-18"
        val SUPPORTED_PROTOCOL_VERSIONS = setOf("2025-06-18", "2025-03-26")
        private val TOOL_NAME = Regex("[A-Za-z0-9_.-]{1,128}")
        private const val MAX_TOOL_PAGES = 20
    }
}
