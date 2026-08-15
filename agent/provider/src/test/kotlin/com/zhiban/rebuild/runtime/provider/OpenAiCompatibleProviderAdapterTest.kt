package com.zhiban.rebuild.runtime.provider

import com.zhiban.rebuild.runtime.runSuspendCatching
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.BufferedSource
import okio.Timeout
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

    @Test fun ioFailureAfterCompleteToolFrameStillFlushesPendingToolCallInFinally() = runTest {
        val events = mutableListOf<ModelEvent>()
        val failure = runSuspendCatching {
            adapter(ThrowAfterFrameBody(toolCallFrame("call-disconnect")))
                .stream(request("disconnect"))
                .collect(events::add)
        }.exceptionOrNull()

        assertTrue("unexpected failure: ${failure?.javaClass?.name}", failure is IOException)
        val toolCalls = events.filterIsInstance<ModelEvent.ToolCall>()
        assertEquals("events=$events", 1, toolCalls.size)
        assertEquals("call-disconnect", toolCalls.single().providerCallId)
        assertEquals(1, events.count { it is ModelEvent.Final })
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

    private fun adapter(body: ResponseBody): OpenAiCompatibleProviderAdapter = OpenAiCompatibleProviderAdapter(
        FixedCallFactory(body),
        resolver,
        registry,
        clock = { 10L },
    )

    private fun request(id: String) = ModelRequest(
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
            setOf("stream", "tools"),
            256_000,
            8_192,
            0,
            1_000,
        ),
        maxTokens = 10,
    )

    private fun toolCallFrame(id: String): String =
        "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"$id\",\"function\":{\"name\":\"calendar.create\",\"arguments\":\"{\\\"title\\\":\\\"demo\\\"}\"}}]}}]}\n"

    private class FixedCallFactory(private val body: ResponseBody) : Call.Factory {
        override fun newCall(request: Request): Call = object : Call {
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
        private val throwingSource = mockk<BufferedSource> {
            every { exhausted() } returns false andThenThrows IOException("simulated_disconnect")
            every { indexOf(any<Byte>(), any(), any()) } returns 0L
            every { readUtf8LineStrict(any()) } returns frame.trimEnd('\n')
            every { close() } just Runs
        }

        override fun contentType(): MediaType = SSE_TYPE

        override fun contentLength(): Long = -1L

        override fun source(): BufferedSource = throwingSource
    }

    private companion object {
        val SSE_TYPE = "text/event-stream".toMediaType()
    }
}
