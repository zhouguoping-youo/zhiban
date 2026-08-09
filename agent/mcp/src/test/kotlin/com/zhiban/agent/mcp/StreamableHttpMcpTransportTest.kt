package com.zhiban.agent.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamableHttpMcpTransportTest {
    @Test fun parsesMatchingJsonRpcEventFromSse() {
        val value = StreamableHttpMcpTransport.parseEventStream(
            "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":\"7\",\"result\":{}}\n\n",
            "7",
        )
        assertEquals("7", value?.get("id").toString().trim('"'))
        assertNull(StreamableHttpMcpTransport.parseEventStream("data: {}\n\n", null))
    }

    @Test fun rejectsNonJsonAndMissingResponseId() {
        assertTrue(
            runCatching {
                StreamableHttpMcpTransport.parseJsonObject("not-json")
            }.exceptionOrNull() is McpProtocolException,
        )
        assertTrue(
            runCatching {
                StreamableHttpMcpTransport.parseEventStream("data: {\"id\":\"x\"}\n", "y")
            }.exceptionOrNull() is McpProtocolException,
        )
    }

    @Test fun joinsMultilineDataAndIgnoresSseMetadata() {
        val value = StreamableHttpMcpTransport.parseEventStream(
            """id: event-1
                |event: message
                |retry: 2000
                |data: {"jsonrpc":"2.0",
                |data: "id":"7","result":{}}
                |
                |
            """.trimMargin(),
            "7",
        )

        assertEquals("7", value?.get("id").toString().trim('"'))
    }

    @Test fun separatesEventsOnBlankLinesAndSupportsCrLf() {
        val events = StreamableHttpMcpTransport.sseDataEvents(
            "data: {\"id\":\"1\"}\r\n\r\ndata: {\"id\":\"2\"}\r\n\r\n",
        )

        assertEquals(listOf("{\"id\":\"1\"}", "{\"id\":\"2\"}"), events)
    }
}
