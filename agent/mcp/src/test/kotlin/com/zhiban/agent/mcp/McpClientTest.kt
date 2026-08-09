package com.zhiban.agent.mcp

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpClientTest {
    @Test fun initializeDiscoverAndCallUseJsonRpcAndValidateIds() = runTest {
        val methods = mutableListOf<String>()
        val client = McpClient(
            McpTransport { request ->
                methods += request.getValue("method").let { (it as JsonPrimitive).content }
                val method = methods.last()
                if (method == "notifications/initialized") return@McpTransport null
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", request.getValue("id"))
                    put(
                        "result",
                        when (method) {
                            "initialize" -> buildJsonObject {
                                put("protocolVersion", McpClient.PROTOCOL_VERSION)
                                put(
                                    "serverInfo",
                                    buildJsonObject {
                                        put("name", "test")
                                        put("version", "1")
                                    },
                                )
                            }

                            "tools/list" -> buildJsonObject {
                                put(
                                    "tools",
                                    JsonArray(
                                        listOf(
                                            buildJsonObject {
                                                put("name", "weather.search")
                                                put("inputSchema", JsonObject(emptyMap()))
                                            },
                                        ),
                                    ),
                                )
                            }

                            else -> buildJsonObject {
                                put(
                                    "content",
                                    JsonArray(
                                        listOf(
                                            buildJsonObject {
                                                put("type", "text")
                                                put("text", "sunny")
                                            },
                                        ),
                                    ),
                                )
                                put("isError", false)
                            }
                        },
                    )
                }
            },
        )
        assertEquals("test", client.initialize().serverInfo.name)
        assertEquals("weather.search", client.listTools().single().name)
        assertTrue(!client.callTool("weather.search", JsonObject(emptyMap())).isError)
        assertEquals(listOf("initialize", "notifications/initialized", "tools/list", "tools/call"), methods)
    }

    @Test fun failsClosedBeforeInitializeAndOnMismatchedResponseId() = runTest {
        val client =
            McpClient(
                McpTransport { request ->
                    buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("id", "wrong")
                        put("result", JsonObject(emptyMap()))
                    }
                },
            )
        assertEquals(
            "MCP_NOT_INITIALIZED",
            runCatching {
                client.listTools()
            }.exceptionOrNull().let { (it as McpProtocolException).safeCode },
        )
        assertEquals(
            "MCP_INVALID_RESPONSE",
            runCatching {
                client.initialize()
            }.exceptionOrNull().let { (it as McpProtocolException).safeCode },
        )
    }
}
