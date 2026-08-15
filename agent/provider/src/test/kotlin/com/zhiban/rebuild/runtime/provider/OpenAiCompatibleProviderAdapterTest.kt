package com.zhiban.rebuild.runtime.provider

import com.zhiban.rebuild.runtime.runSuspendCatching
import java.io.IOException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiCompatibleProviderAdapterTest {
    private val registry = TrustedProviderRegistry()
    private val profile = ProviderProfile("stepfun", "stepfun-cn-openai-v1", "step-3.5-flash", "test.credential", 1)
    private val resolver = object : CredentialResolver {
        override suspend fun <T> withCredential(credentialRef: String, keyVersion: Int, block: suspend (ByteArray) -> T): T {
            val credential = "test-only-credential".encodeToByteArray()
            return try {
                block(credential)
            } finally {
                credential.fill(0)
            }
        }
    }

    @Test fun normalSseStreamEmitsExactlyOneTerminalEvent() = runTest {
        val body = listOf(
            "data: {\"choices\":[{\"delta\":{\"content\":\"完成\"},\"finish_reason\":\"stop\"}]}",
            "data: [DONE]",
        ).joinToString("\n\n", postfix = "\n")

        val events = adapter(body.toResponseBody(SSE_TYPE)).stream(request("normal")).toList()

        assertEquals(1, events.count { it is ModelEvent.Final })
        assertEquals("完成", events.filterIsInstance<ModelEvent.Delta>().joinToString("") { it.text })
    }

    @Test fun cleanEofWithoutDoneFlushesPendingToolCall() = runTest {
        val body = toolCallFrame("call-eof").toResponseBody(SSE_TYPE)

        val events = adapter(body).stream(request("clean-eof")).toList()

        assertEquals("call-eof", events.filterIsInstance<ModelEvent.ToolCall>().single().providerCallId)
        assertEquals(1, events.count { it is ModelEvent.Final })
    }

    @Test fun ioFailureAfterCompleteToolFrameRecoversPendingToolCallWithoutRacingTheFailure() = runTest {
        val events = mutableListOf<ModelEvent>()
        val body = ThrowAfterFrameBody(toolCallFrame("call-disconnect"))
        val failure = runSuspendCatching {
            adapter(body)
                .stream(request("disconnect"))
                .collect(events::add)
        }.exceptionOrNull()

        assertEquals("complete tool call should recover the interrupted stream", null, failure)
        val toolCalls = events.filterIsInstance<ModelEvent.ToolCall>()
        assertEquals("events=$events; reads=${body.readCount}", 1, toolCalls.size)
        assertEquals("call-disconnect", toolCalls.single().providerCallId)
        assertEquals(1, events.count { it is ModelEvent.Final })
    }

    @Test fun ioFailureAfterPartialTextDoesNotPretendTheResponseCompleted() = runTest {
        val events = mutableListOf<ModelEvent>()
        val failure = runSuspendCatching {
            adapter(ThrowAfterFrameBody("data: {\"choices\":[{\"delta\":{\"content\":\"半截回答\"}}]}\n"))
                .stream(request("partial-text-disconnect"))
                .collect(events::add)
        }.exceptionOrNull()

        assertTrue("unexpected failure: ${failure?.javaClass?.name}", failure is IOException)
        assertTrue(events.none { it is ModelEvent.Final })
    }

    @Test fun protocolFailureDoesNotFinalizeOrExposeAPartialToolCall() = runTest {
        val events = mutableListOf<ModelEvent>()
        val body = (toolCallFrame("call-partial") + "data: {not-json}\n").toResponseBody(SSE_TYPE)
        val failure = runSuspendCatching {
            adapter(body).stream(request("protocol-failure")).collect(events::add)
        }.exceptionOrNull()

        assertTrue(failure is ProviderFailure)
        assertEquals("PROVIDER_PROTOCOL_ERROR", (failure as ProviderFailure).code)
        assertTrue(events.none { it is ModelEvent.ToolCall || it is ModelEvent.Final })
    }

    @Test fun explicitNullFieldsDoNotFinalizeOrEmitLiteralNullDuringFragmentedToolCall() = runTest {
        val body = listOf(
            """data: {"choices":[{"delta":{"content":null,"tool_calls":[{"index":0,"id":"call-null","function":{"name":"calendar.create","arguments":"{\"title\":"}}]},"finish_reason":null}]}""",
            """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"demo\"}"}}]},"finish_reason":"tool_calls"}]}""",
            "data: [DONE]",
        ).joinToString("\n\n", postfix = "\n")

        val events = adapter(body.toResponseBody(SSE_TYPE)).stream(request("explicit-null")).toList()

        assertTrue(events.none { it is ModelEvent.Delta && it.text == "null" })
        assertEquals("call-null", events.filterIsInstance<ModelEvent.ToolCall>().single().providerCallId)
        assertEquals(1, events.count { it is ModelEvent.Final })
        assertEquals("tool_calls", events.filterIsInstance<ModelEvent.Final>().single().finishReason)
    }

    @Test fun webSearchIsSentAsProviderToolAndSourcesDoNotBecomeRuntimeToolCalls() = runTest {
        val body = listOf(
            """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"web-1","type":"web_search","function":{"name":"step_websearch","results":[{"url":"https://example.com/news#section","title":"可靠来源"},{"url":"javascript:alert(1)","title":"恶意来源"}]}}]}}]}""",
            """data: {"choices":[{"delta":{"content":"这是最新结果"},"finish_reason":"stop"}]}""",
            "data: [DONE]",
        ).joinToString("\n\n", postfix = "\n")
        val calls = FixedCallFactory(body.toResponseBody(SSE_TYPE))

        val events = adapter(calls).stream(request("web", allowWebSearch = true)).toList()

        val requestBody = calls.lastRequestBody()
        val tools = (Json.parseToJsonElement(requestBody) as JsonObject)["tools"] as JsonArray
        assertEquals("web_search", ((tools.single() as JsonObject)["type"] as JsonPrimitive).contentOrNull)
        assertTrue(events.none { it is ModelEvent.ToolCall })
        assertEquals(
            "这是最新结果\n\n来源：\n- [可靠来源](https://example.com/news)",
            events.filterIsInstance<ModelEvent.Delta>().joinToString("") { it.text },
        )
    }

    @Test fun webSearchRequiresAnAdvertisedCapability() = runTest {
        val failure = runSuspendCatching {
            adapter("data: [DONE]\n".toResponseBody(SSE_TYPE))
                .stream(request("unsupported-web", allowWebSearch = true, webSearchCapability = false))
                .collect()
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals("WEB_SEARCH_UNSUPPORTED", failure?.message)
    }

    private fun adapter(body: ResponseBody): OpenAiCompatibleProviderAdapter = adapter(FixedCallFactory(body))

    private fun adapter(calls: Call.Factory): OpenAiCompatibleProviderAdapter = OpenAiCompatibleProviderAdapter(
        calls,
        resolver,
        registry,
        clock = { 10L },
    )

    private fun request(id: String, allowWebSearch: Boolean = false, webSearchCapability: Boolean = true) = ModelRequest(
        requestId = id,
        channel = OutboundChannel.LLM_INFERENCE,
        profile = profile,
        messages = listOf(
            ModelMessage(
                "user",
                "test",
                OutboundSensitivity.PUBLIC,
                OutboundPurpose.USER_AUTHORED,
                OutboundProvenance("provider_test", id),
            ),
        ),
        capability = CapabilitySnapshot(
            registry.digest(profile),
            setOf("text"),
            buildSet {
                add("stream")
                add("tools")
                if (webSearchCapability) add("web_search")
            },
            256_000,
            8_192,
            0,
            1_000,
        ),
        maxTokens = 10,
        allowWebSearch = allowWebSearch,
    )

    private fun toolCallFrame(id: String): String =
        "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"$id\",\"function\":{\"name\":\"calendar.create\",\"arguments\":\"{\\\"title\\\":\\\"demo\\\"}\"}}]}}]}\n"

    private class FixedCallFactory(private val body: ResponseBody) : Call.Factory {
        private var request: Request? = null

        fun lastRequestBody(): String {
            val buffer = Buffer()
            checkNotNull(request).body?.writeTo(buffer)
            return buffer.readUtf8()
        }

        override fun newCall(request: Request): Call = object : Call {
            init {
                this@FixedCallFactory.request = request
            }
            private var isExecuted = false
            private var isCancelled = false

            override fun request(): Request = request

            override fun execute(): Response {
                isExecuted = true
                return Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("test")
                    .body(body)
                    .build()
            }

            override fun enqueue(responseCallback: Callback): Unit = error("unused")

            override fun cancel() {
                isCancelled = true
            }

            override fun isExecuted(): Boolean = isExecuted

            override fun isCanceled(): Boolean = isCancelled

            override fun timeout(): Timeout = Timeout.NONE

            override fun clone(): Call = newCall(request)
        }
    }

    private class ThrowAfterFrameBody(frame: String) : ResponseBody() {
        private val frameBytes = frame.encodeToByteArray()
        var readCount: Int = 0
            private set
        private val throwingSource: BufferedSource = object : Source {
            private var frameOffset = 0

            override fun read(sink: Buffer, byteCount: Long): Long {
                readCount += 1
                if (frameOffset == frameBytes.size) throw IOException("simulated_disconnect")
                val count = minOf(byteCount.toInt(), frameBytes.size - frameOffset)
                sink.write(frameBytes, frameOffset, count)
                frameOffset += count
                return count.toLong()
            }

            override fun timeout(): Timeout = Timeout.NONE

            override fun close() = Unit
        }.buffer()

        override fun contentType(): MediaType = SSE_TYPE

        override fun contentLength(): Long = -1L

        override fun source(): BufferedSource = throwingSource
    }

    private companion object {
        val SSE_TYPE = "text/event-stream".toMediaType()
    }
}
