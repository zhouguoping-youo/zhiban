package com.zhiban.rebuild.runtime.provider

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLPeerUnverifiedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Timeout
import org.junit.Assert.*
import org.junit.Test

class ProviderModuleTest {
    @Test fun stepFunIsTheOnlyProviderAndSeparatesTextFromVision() {
        val preset = registry.preset("stepfun")
        assertEquals(
            listOf(TrustedProviderRegistry.STEPFUN_TEXT_MODEL, TrustedProviderRegistry.STEPFUN_VISION_MODEL),
            preset.models,
        )
        assertEquals(TrustedProviderRegistry.STEPFUN_TEXT_MODEL, preset.defaultModel)
        val endpoint = registry.resolve(
            ProviderProfile("stepfun", preset.endpointId, preset.defaultModel, "stepfun.primary", 1),
        )
        assertEquals(
            setOf("text"),
            endpoint.modelContracts.getValue(TrustedProviderRegistry.STEPFUN_TEXT_MODEL).modalities,
        )
        assertTrue("image" in endpoint.modelContracts.getValue(TrustedProviderRegistry.STEPFUN_VISION_MODEL).modalities)
        assertTrue("stream" in endpoint.modelContracts.getValue(TrustedProviderRegistry.STEPFUN_TEXT_MODEL).features)
        assertEquals(1, registry.presets().size)
    }

    @Test fun imageAttachmentUsesOpenAiMultimodalContentWithoutLeakingPrivateCacheRef() = runBlocking {
        val factory =
            QueueCallFactory(
                response(200, "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n"),
            )
        val adapter = OpenAiCompatibleProviderAdapter(
            factory,
            resolver,
            registry,
            clock = { 10 },
            attachments = object : ProviderAttachmentResolver {
                override fun imageDataUrls(attachment: ModelAttachment, nowEpochMs: Long) = listOf("data:image/png;base64,iVBORw0KGgo=")
            },
        )
        val attachment = ModelAttachment(
            "image-1", "IMAGE", "image/png", 8, "a".repeat(64),
            "cache://private-file.bin", 100, OutboundSensitivity.SENSITIVE,
            OutboundPurpose.USER_SELECTED_ATTACHMENT, OutboundProvenance("test_attachment", "image-1"),
        )
        adapter.stream(
            ModelRequest(
                "vision",
                OutboundChannel.LLM_INFERENCE,
                profile,
                listOf(userMessage("描述图片")),
                capability(100).copy(modalities = setOf("text", "image")),
                20,
                attachments = listOf(attachment),
            ),
        ).toList()
        val body = requireNotNull(factory.requests.single().body).let { okio.Buffer().also(it::writeTo).readUtf8() }
        assertTrue(body.contains("image_url"))
        assertTrue(body.contains("data:image/png;base64"))
        assertFalse(body.contains("cache://"))
        assertFalse(body.contains("private-file"))
    }

    private val profile = ProviderProfile("stepfun", "stepfun-cn-openai-v1", "step-3.5-flash", "credential.main", 2)
    private val registry = TrustedProviderRegistry()
    private val credential = "sk-CANARY-123456789".toByteArray()
    private val resolver = object : CredentialResolver {
        override suspend fun <T> withCredential(credentialRef: String, keyVersion: Int, block: suspend (ByteArray) -> T): T {
            assertEquals(profile.credentialRef, credentialRef)
            assertEquals(profile.keyVersion, keyVersion)
            val copy = credential.copyOf()
            return try {
                block(copy)
            } finally {
                copy.fill(0)
            }
        }
    }

    @Test fun trustedBindingAndCapabilityExpiryFailClosed() {
        assertEquals("https://api.stepfun.com/v1/chat/completions", registry.resolve(profile).chatUrl)
        assertThrows(IllegalStateException::class.java) {
            registry.resolve(profile.copy(endpointId = "https://evil.invalid"))
        }
        assertThrows(IllegalStateException::class.java) { registry.resolve(profile.copy(modelId = "unknown")) }
        val snapshot = capability(expires = 99)
        assertThrows(IllegalStateException::class.java) { snapshot.requireFresh(100, registry.digest(profile)) }
        assertThrows(IllegalStateException::class.java) {
            snapshot.copy(expiresAtEpochMs = 200).requireFresh(100, "wrong")
        }
        assertEquals(256_000, registry.resolve(profile).modelContracts.getValue(profile.modelId).maxContextTokens)
        assertEquals(8_192, registry.resolve(profile).modelContracts.getValue(profile.modelId).maxOutputTokens)
        assertEquals(setOf("text"), registry.resolve(profile).modelContracts.getValue(profile.modelId).modalities)
        assertEquals("step-3.5-flash", registry.preset("stepfun").defaultModel)
    }

    @Test fun redactorRemovesCanaryBearerAndRejectsUnsafeRequestId() {
        val redactor = SecretRedactor()
        val canary = credential.decodeToString()
        val value = "Authorization: Bearer $canary api_key=$canary"
        val safe = redactor.redact(value, listOf(canary))
        assertFalse(safe.contains("CANARY"))
        assertFalse(safe.contains("sk-"))
        assertFalse(redactor.redactHeaders(mapOf("Authorization" to "Bearer $canary")).toString().contains("CANARY"))
        assertFalse(redactor.redactUrl("https://safe.invalid/path?api_key=$canary&x=1").contains("CANARY"))
        assertFalse(redactor.redactJson("{\"outer\":{\"token\":\"$canary\"}}").contains("CANARY"))
        assertFalse(
            redactor.redactThrowable(
                IllegalStateException("wrapper", IllegalArgumentException("Bearer $canary")),
            ).contains("CANARY"),
        )
        assertNull(redactor.safeRequestId("request id with spaces"))
        assertEquals("req_123", redactor.safeRequestId("req_123"))
    }

    @Test fun redactorRemovesPersonalIdentifiersFromDiagnostics() {
        val safe = SecretRedactor().redact(
            "phone=13812345678 email=person@example.com id=11010519491231002X",
        )
        assertFalse(safe.contains("13812345678"))
        assertFalse(safe.contains("person@example.com"))
        assertFalse(safe.contains("11010519491231002X"))
        assertTrue(safe.contains("[REDACTED_PHONE]"))
        assertTrue(safe.contains("[REDACTED_EMAIL]"))
        assertTrue(safe.contains("[REDACTED_ID]"))
    }

    @Test fun probeStreamSchemaUsageAndErrorMapping() = runBlocking {
        val factory = QueueCallFactory(
            response(200, "{\"object\":\"list\",\"data\":[{\"id\":\"step-3.5-flash\"}]}"),
            response(
                200,
                "data: {\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}\n\ndata: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":1}}\n\ndata: [DONE]\n",
            ),
        )
        val adapter = OpenAiCompatibleProviderAdapter(factory, resolver, registry, clock = { 10 })
        val capability = adapter.probe(profile)
        val events = adapter.stream(
            ModelRequest("req1", OutboundChannel.LLM_INFERENCE, profile, listOf(userMessage("hello")), capability, 20),
        ).toList()
        assertTrue(events.contains(ModelEvent.Delta(0, "hi")))
        assertTrue(events.contains(ModelEvent.Usage(3, 1)))
        assertTrue(events.contains(ModelEvent.Final("stop")))
        val sent = factory.requests.last()
        assertEquals("Bearer ${credential.decodeToString()}", sent.header("Authorization"))
        val sentBody = requireNotNull(sent.body).let { body -> okio.Buffer().also(body::writeTo).readUtf8() }
        assertTrue(sentBody.contains("max_tokens"))
        assertFalse(sentBody.contains("stream_options"))
        val unsupportedSchema =
            ModelRequest(
                "schema",
                OutboundChannel.LLM_INFERENCE,
                profile,
                listOf(userMessage("hello")),
                capability,
                20,
                "{\"type\":\"object\"}",
            )
        assertEquals(
            "SCHEMA_UNSUPPORTED",
            runCatching {
                adapter.stream(unsupportedSchema).toList()
            }.exceptionOrNull()?.message,
        )

        val failing =
            OpenAiCompatibleProviderAdapter(
                QueueCallFactory(response(429, "no secret", mapOf("Retry-After" to "2", "x-request-id" to "req_safe"))),
                resolver,
                registry,
            )
        val failure = runCatching { failing.probe(profile) }.exceptionOrNull() as ProviderFailure
        assertEquals("RATE_LIMITED", failure.code)
        assertTrue(failure.retryable)
        assertEquals(2_000L, failure.retryAfterMillis)
        assertEquals("req_safe", failure.safeRequestId)
    }

    @Test fun stepFunHttpErrorsMapSafeRequestId() = runBlocking {
        val http = OpenAiCompatibleProviderAdapter(
            QueueCallFactory(
                response(
                    401,
                    "{}",
                    mapOf("x-request-id" to "req_safe"),
                ),
            ),
            resolver,
            registry,
        )
        val httpFailure = runCatching { http.probe(profile) }.exceptionOrNull() as ProviderFailure
        assertEquals("AUTHENTICATION_FAILED", httpFailure.code)
        assertEquals("req_safe", httpFailure.safeRequestId)
    }

    @Test fun tlsVerificationFailureIsSafeAndNeverRetryableForProbeOrStream() = runBlocking {
        val adapter = OpenAiCompatibleProviderAdapter(ThrowingCallFactory(), resolver, registry, clock = { 10 })

        val probeFailure = runCatching { adapter.probe(profile) }.exceptionOrNull() as ProviderFailure
        assertEquals("TLS_VERIFICATION_FAILED", probeFailure.code)
        assertFalse(probeFailure.retryable)

        val streamFailure = runCatching {
            adapter.stream(
                ModelRequest(
                    "tls-stream",
                    OutboundChannel.LLM_INFERENCE,
                    profile,
                    listOf(userMessage("hello")),
                    capability(100),
                    20,
                ),
            ).toList()
        }.exceptionOrNull() as ProviderFailure
        assertEquals("TLS_VERIFICATION_FAILED", streamFailure.code)
        assertFalse(streamFailure.retryable)
    }

    @Test fun standardCompatibleProviderErrorsAreMappedWithoutExposingMessages() = runBlocking {
        val cases = listOf(
            "{\"error\":{\"code\":\"InvalidApiKey\",\"message\":\"secret detail\"}}" to "AUTHENTICATION_FAILED",
            "{\"error\":{\"code\":\"Arrearage\",\"message\":\"billing detail\"}}" to "INSUFFICIENT_QUOTA",
            "{\"error\":{\"code\":\"Throttling.RateQuota\",\"message\":\"limit detail\"}}" to "RATE_LIMITED",
            "{\"error\":{\"code\":\"400003 CodeInputTooLong\",\"request_id\":\"req_safe\"}}" to
                "CONTEXT_TOKEN_LIMIT_EXCEEDED",
            "{\"error\":{\"code\":\"400002 CodeInvalidParameter\"}}" to "INVALID_REQUEST",
        )
        cases.forEach { (body, expected) ->
            val adapter = OpenAiCompatibleProviderAdapter(QueueCallFactory(response(400, body)), resolver, registry)
            val failure = runCatching { adapter.probe(profile) }.exceptionOrNull() as ProviderFailure
            assertEquals(expected, failure.code)
            assertFalse(failure.message.orEmpty().contains("detail"))
        }
    }

    @Test fun probeFailsClosedWhenBoundModelIsAbsent() = runBlocking {
        val adapter = OpenAiCompatibleProviderAdapter(
            QueueCallFactory(response(200, "{\"object\":\"list\",\"data\":[{\"id\":\"MiniMax-M2.5\"}]}")),
            resolver,
            registry,
        )
        val failure = runCatching { adapter.probe(profile) }.exceptionOrNull() as ProviderFailure
        assertEquals("MODEL_NOT_AVAILABLE", failure.code)
    }

    @Test fun outputLimitAndTotalContextFailClosed() = runBlocking {
        val adapter = OpenAiCompatibleProviderAdapter(QueueCallFactory(), resolver, registry, clock = { 10 })
        val outputTooLarge =
            ModelRequest(
                "output",
                OutboundChannel.LLM_INFERENCE,
                profile,
                listOf(userMessage("x")),
                capability(100),
                2_049,
            )
        assertEquals(
            "OUTPUT_TOKEN_LIMIT_EXCEEDED",
            runCatching {
                adapter.stream(outputTooLarge).toList()
            }.exceptionOrNull()?.message,
        )
        val contextTooLarge =
            ModelRequest(
                "context",
                OutboundChannel.LLM_INFERENCE,
                profile,
                listOf(userMessage("x".repeat(819_200))),
                capability(100),
                1,
            )
        assertEquals(
            "CONTEXT_TOKEN_LIMIT_EXCEEDED",
            runCatching {
                adapter.stream(contextTooLarge).toList()
            }.exceptionOrNull()?.message,
        )
        listOf("中".repeat(70_000), "🙂".repeat(52_000)).forEachIndexed { index, content ->
            val request =
                ModelRequest(
                    "unicode-$index",
                    OutboundChannel.LLM_INFERENCE,
                    profile,
                    listOf(userMessage(content)),
                    capability(100),
                    1,
                )
            assertEquals(
                "CONTEXT_TOKEN_LIMIT_EXCEEDED",
                runCatching {
                    adapter.stream(request).toList()
                }.exceptionOrNull()?.message,
            )
        }
    }

    @Test fun fragmentedToolArgumentsEmitOneCompleteValidatedCall() = runBlocking {
        val stream = listOf(
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call-1\",\"function\":{\"name\":\"calendar.create\",\"arguments\":\"{\\\"title\\\":\"}}]}}]}",
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"\\\"demo\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}]}",
            "data: [DONE]",
        ).joinToString("\n\n", postfix = "\n")
        val adapter =
            OpenAiCompatibleProviderAdapter(QueueCallFactory(response(200, stream)), resolver, registry, clock = { 10 })
        val events = adapter.stream(
            ModelRequest("tool", OutboundChannel.LLM_INFERENCE, profile, listOf(userMessage("x")), capability(100), 10),
        ).toList()
        assertEquals(
            listOf(
                ModelEvent.ToolCall(0, "call-1", "calendar.create", "{\"title\":\"demo\"}"),
                ModelEvent.Final("tool_calls"),
            ),
            events,
        )
    }

    @Test fun toolCallIsFlushedWhenDoneArrivesWithoutFinishReason() = runBlocking {
        val stream = listOf(
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call-done\",\"function\":{\"name\":\"calendar.create\",\"arguments\":\"{\\\"title\\\":\\\"demo\\\"}\"}}]}}]}",
            "data: [DONE]",
        ).joinToString("\n\n", postfix = "\n")
        val adapter =
            OpenAiCompatibleProviderAdapter(QueueCallFactory(response(200, stream)), resolver, registry, clock = { 10 })

        val events = adapter.stream(
            ModelRequest("tool-done", OutboundChannel.LLM_INFERENCE, profile, listOf(userMessage("x")), capability(100), 10),
        ).toList()

        assertEquals(
            listOf(
                ModelEvent.ToolCall(0, "call-done", "calendar.create", "{\"title\":\"demo\"}"),
                ModelEvent.Final("tool_calls"),
            ),
            events,
        )
    }

    @Test fun completeToolCallIsFlushedWhenStreamEndsAtCleanEofWithoutDone() = runBlocking {
        val stream =
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call-eof\",\"function\":{\"name\":\"calendar.create\",\"arguments\":\"{\\\"title\\\":\\\"demo\\\"}\"}}]}}]}\n"
        val adapter =
            OpenAiCompatibleProviderAdapter(QueueCallFactory(response(200, stream)), resolver, registry, clock = { 10 })

        val events = adapter.stream(
            ModelRequest("tool-eof", OutboundChannel.LLM_INFERENCE, profile, listOf(userMessage("x")), capability(100), 10),
        ).toList()

        assertEquals(
            listOf(
                ModelEvent.ToolCall(0, "call-eof", "calendar.create", "{\"title\":\"demo\"}"),
                ModelEvent.Final("tool_calls"),
            ),
            events,
        )
    }

    @Test fun plainContentAtCleanEofEmitsExactlyOneFinal() = runBlocking {
        val adapter = OpenAiCompatibleProviderAdapter(
            QueueCallFactory(response(200, "data: {\"choices\":[{\"delta\":{\"content\":\"done\"}}]}\n")),
            resolver,
            registry,
            clock = { 10 },
        )

        val events = adapter.stream(
            ModelRequest("plain-eof", OutboundChannel.LLM_INFERENCE, profile, listOf(userMessage("x")), capability(100), 10),
        ).toList()

        assertEquals(listOf(ModelEvent.Delta(0, "done"), ModelEvent.Final("stop")), events)
        assertEquals(1, events.count { it is ModelEvent.Final })
    }

    @Test fun cleanEofWithoutTrailingNewlineStillDecodesTheLastSseFrame() = runBlocking {
        val adapter = OpenAiCompatibleProviderAdapter(
            QueueCallFactory(response(200, "data: {\"choices\":[{\"delta\":{\"content\":\"done\"}}]}")),
            resolver,
            registry,
            clock = { 10 },
        )

        val events = adapter.stream(
            ModelRequest(
                "plain-eof-no-newline",
                OutboundChannel.LLM_INFERENCE,
                profile,
                listOf(userMessage("x")),
                capability(100),
                10,
            ),
        ).toList()

        assertEquals(listOf(ModelEvent.Delta(0, "done"), ModelEvent.Final("stop")), events)
        assertEquals(1, events.count { it is ModelEvent.Final })
    }

    @Test fun doneWithoutFinishReasonEmitsExactlyOneFinal() = runBlocking {
        val stream = listOf(
            "data: {\"choices\":[{\"delta\":{\"content\":\"done\"}}]}",
            "data: [DONE]",
        ).joinToString("\n\n", postfix = "\n")
        val adapter =
            OpenAiCompatibleProviderAdapter(QueueCallFactory(response(200, stream)), resolver, registry, clock = { 10 })

        val events = adapter.stream(
            ModelRequest("plain-done", OutboundChannel.LLM_INFERENCE, profile, listOf(userMessage("x")), capability(100), 10),
        ).toList()

        assertEquals(listOf(ModelEvent.Delta(0, "done"), ModelEvent.Final("stop")), events)
        assertEquals(1, events.count { it is ModelEvent.Final })
    }

    @Test fun malformedSseChunkReturnsFixedProtocolFailure() = runBlocking {
        val adapter = OpenAiCompatibleProviderAdapter(
            QueueCallFactory(response(200, "data: {not-json}\n\ndata: [DONE]\n")),
            resolver,
            registry,
            clock = { 10 },
        )

        val failure = runCatching {
            adapter.stream(
                ModelRequest("malformed", OutboundChannel.LLM_INFERENCE, profile, listOf(userMessage("x")), capability(100), 10),
            ).toList()
        }.exceptionOrNull() as ProviderFailure

        assertEquals("PROVIDER_PROTOCOL_ERROR", failure.code)
        assertTrue(failure.retryable)
    }

    @Test fun providerErrorInsideSuccessfulSseResponseIsNeverTreatedAsAnEmptySuccess() = runBlocking {
        val stream = listOf(
            "data: {\"error\":{\"code\":\"InvalidApiKey\",\"message\":\"secret detail\",\"request_id\":\"req_safe\"}}",
            "data: [DONE]",
        ).joinToString("\n\n", postfix = "\n")
        val adapter = OpenAiCompatibleProviderAdapter(
            QueueCallFactory(response(200, stream)),
            resolver,
            registry,
            clock = { 10 },
        )

        val failure = runCatching {
            adapter.stream(
                ModelRequest(
                    "sse-error",
                    OutboundChannel.LLM_INFERENCE,
                    profile,
                    listOf(userMessage("x")),
                    capability(100),
                    10,
                ),
            ).toList()
        }.exceptionOrNull() as ProviderFailure

        assertEquals("AUTHENTICATION_FAILED", failure.code)
        assertEquals("req_safe", failure.safeRequestId)
        assertFalse(failure.message.orEmpty().contains("secret detail"))
    }

    @Test fun stepFunStopFinishReasonDoesNotDiscardAValidToolCall() = runBlocking {
        val stream = listOf(
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call-step\",\"function\":{\"name\":\"calendar.create\",\"arguments\":\"{\\\"title\\\":\\\"demo\\\"}\"}}]}}]}",
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}",
            "data: [DONE]",
        ).joinToString("\n\n", postfix = "\n")
        val adapter =
            OpenAiCompatibleProviderAdapter(QueueCallFactory(response(200, stream)), resolver, registry, clock = { 10 })
        val events = adapter.stream(
            ModelRequest(
                "step-tool",
                OutboundChannel.LLM_INFERENCE,
                profile,
                listOf(userMessage("x")),
                capability(100),
                10,
            ),
        ).toList()
        assertEquals(
            listOf(
                ModelEvent.ToolCall(0, "call-step", "calendar.create", "{\"title\":\"demo\"}"),
                ModelEvent.Final("stop"),
            ),
            events,
        )
    }

    @Test fun forcedToolChoiceUsesTheProviderSafeFunctionName() = runBlocking {
        val factory =
            QueueCallFactory(
                response(200, "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n"),
            )
        val adapter = OpenAiCompatibleProviderAdapter(factory, resolver, registry, clock = { 10 })
        adapter.stream(
            ModelRequest(
                "forced-tool",
                OutboundChannel.LLM_INFERENCE,
                profile,
                listOf(userMessage("x")),
                capability(100),
                10,
                toolsJson = """[{"type":"function","function":{"name":"calendar_schedule_create","parameters":{"type":"object"}}}]""",
                forcedToolName = "calendar_schedule_create",
            ),
        ).toList()
        val buffer = Buffer()
        factory.requests.single().body!!.writeTo(buffer)
        val body = Json.parseToJsonElement(buffer.readUtf8()).jsonObject
        assertEquals(
            "calendar_schedule_create",
            body["tool_choice"]!!.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content,
        )
    }

    @Test fun interleavedToolFragmentsKeepStableIdentityAndRejectConflicts() = runBlocking {
        val stream = listOf(
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":1,\"id\":\"call-b\",\"function\":{\"name\":\"b\",\"arguments\":\"{\"}},{\"index\":0,\"id\":\"call-a\",\"function\":{\"name\":\"a\",\"arguments\":\"{\"}}]}}]}",
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"}\"}},{\"index\":1,\"function\":{\"arguments\":\"}\"}}]},\"finish_reason\":\"tool_calls\"}]}",
        ).joinToString("\n\n", postfix = "\n")
        val adapter =
            OpenAiCompatibleProviderAdapter(QueueCallFactory(response(200, stream)), resolver, registry, clock = { 10 })
        val events = adapter.stream(
            ModelRequest(
                "interleaved",
                OutboundChannel.LLM_INFERENCE,
                profile,
                listOf(userMessage("x")),
                capability(100),
                10,
            ),
        ).toList()
        assertEquals(
            listOf("call-a", "call-b"),
            events.filterIsInstance<ModelEvent.ToolCall>().map {
                it.providerCallId
            },
        )

        val conflict = """
            data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call-a","function":{"name":"a","arguments":"{"}},{"index":0,"id":"call-b","function":{"arguments":"}"}}]}}]}
        """.trimIndent() + "\n"
        val conflicting =
            OpenAiCompatibleProviderAdapter(QueueCallFactory(response(200, conflict)), resolver, registry, clock = {
                10
            })
        assertEquals(
            "TOOL_CALL_ID_CONFLICT",
            runCatching {
                conflicting.stream(
                    ModelRequest(
                        "conflict",
                        OutboundChannel.LLM_INFERENCE,
                        profile,
                        listOf(userMessage("x")),
                        capability(100),
                        10,
                    ),
                ).toList()
            }.exceptionOrNull()?.message,
        )

        val reusedId = """
            data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call-a","function":{"name":"a","arguments":"{"}},{"index":1,"id":"call-a","function":{"name":"b","arguments":"}"}}]}}]}
        """.trimIndent() + "\n"
        val reused =
            OpenAiCompatibleProviderAdapter(QueueCallFactory(response(200, reusedId)), resolver, registry, clock = {
                10
            })
        assertEquals(
            "TOOL_CALL_ID_CONFLICT",
            runCatching {
                reused.stream(
                    ModelRequest(
                        "reused",
                        OutboundChannel.LLM_INFERENCE,
                        profile,
                        listOf(userMessage("x")),
                        capability(100),
                        10,
                    ),
                ).toList()
            }.exceptionOrNull()?.message,
        )
    }

    @Test fun oversizedModelsBodyAndSseFrameCancelBeforeUnboundedRead() = runBlocking {
        val modelFactory = QueueCallFactory(response(200, "x".repeat(262_145)))
        val probe = OpenAiCompatibleProviderAdapter(modelFactory, resolver, registry)
        assertEquals("PROVIDER_RESPONSE_TOO_LARGE", runCatching { probe.probe(profile) }.exceptionOrNull()?.message)
        assertTrue(modelFactory.calls.single().isCanceled())

        val sseFactory = QueueCallFactory(response(200, "data: ${"x".repeat(65_537)}\n"))
        val stream = OpenAiCompatibleProviderAdapter(sseFactory, resolver, registry, clock = { 10 })
        assertEquals(
            "PROVIDER_FRAME_TOO_LARGE",
            runCatching {
                stream.stream(
                    ModelRequest(
                        "frame",
                        OutboundChannel.LLM_INFERENCE,
                        profile,
                        listOf(userMessage("x")),
                        capability(100),
                        10,
                    ),
                ).toList()
            }.exceptionOrNull()?.message,
        )
        assertTrue(sseFactory.calls.single().isCanceled())
    }

    @Test fun invalidOrOversizedToolArgumentsFailClosed() = runBlocking {
        fun payload(arguments: String) =
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"name\":\"calendar.create\",\"arguments\":${JsonPrimitive(
                arguments,
            )}}}]},\"finish_reason\":\"tool_calls\"}]}\n"
        val invalid =
            OpenAiCompatibleProviderAdapter(QueueCallFactory(response(200, payload("{"))), resolver, registry, clock = {
                10
            })
        assertEquals(
            "INVALID_TOOL_ARGUMENTS",
            runCatching {
                invalid.stream(
                    ModelRequest(
                        "invalid",
                        OutboundChannel.LLM_INFERENCE,
                        profile,
                        listOf(userMessage("x")),
                        capability(100),
                        10,
                    ),
                ).toList()
            }.exceptionOrNull()?.message,
        )
        val oversizedStream = listOf(
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call-large\"," +
                "\"function\":{\"name\":\"calendar.create\",\"arguments\":${JsonPrimitive(
                    "x".repeat(40_000),
                )}}}]}}]}",
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":${JsonPrimitive(
                "x".repeat(25_537),
            )}}}]}}]}\n",
        ).joinToString("\n")
        val oversized =
            OpenAiCompatibleProviderAdapter(
                QueueCallFactory(
                    response(200, oversizedStream),
                ),
                resolver,
                registry,
                clock = {
                    10
                },
            )
        assertEquals(
            "TOOL_ARGUMENTS_TOO_LARGE",
            runCatching {
                oversized.stream(
                    ModelRequest(
                        "large",
                        OutboundChannel.LLM_INFERENCE,
                        profile,
                        listOf(userMessage("x")),
                        capability(100),
                        10,
                    ),
                ).toList()
            }.exceptionOrNull()?.message,
        )
    }

    @Test fun usageAndFinalAreEmittedAtMostOnce() = runBlocking {
        val stream = listOf(
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":1}}",
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":1}}",
            "data: [DONE]",
        ).joinToString("\n\n", postfix = "\n")
        val adapter =
            OpenAiCompatibleProviderAdapter(QueueCallFactory(response(200, stream)), resolver, registry, clock = { 10 })
        val events = adapter.stream(
            ModelRequest(
                "terminal",
                OutboundChannel.LLM_INFERENCE,
                profile,
                listOf(userMessage("x")),
                capability(100),
                10,
            ),
        ).toList()
        assertEquals(1, events.count { it is ModelEvent.Usage })
        assertEquals(1, events.count { it is ModelEvent.Final })
    }

    @Test fun fragmentedReasoningTagsAreNeverProjectedAsVisibleDeltas() = runBlocking {
        val stream = listOf(
            "data: {\"choices\":[{\"delta\":{\"content\":\"<thi\"}}]}",
            "data: {\"choices\":[{\"delta\":{\"content\":\"nk>private reasoning\"}}]}",
            "data: {\"choices\":[{\"delta\":{\"content\":\" continues</thi\"}}]}",
            "data: {\"choices\":[{\"delta\":{\"content\":\"nk>O\"}}]}",
            "data: {\"choices\":[{\"delta\":{\"content\":\"K\"},\"finish_reason\":\"stop\"}]}",
            "data: [DONE]",
        ).joinToString("\n\n", postfix = "\n")
        val adapter =
            OpenAiCompatibleProviderAdapter(QueueCallFactory(response(200, stream)), resolver, registry, clock = { 10 })

        val events = adapter.stream(
            ModelRequest(
                "reasoning",
                OutboundChannel.LLM_INFERENCE,
                profile,
                listOf(userMessage("Reply only OK")),
                capability(100),
                10,
            ),
        ).toList()

        assertEquals("OK", events.filterIsInstance<ModelEvent.Delta>().joinToString("") { it.text })
        assertTrue(events.contains(ModelEvent.Final("stop")))
    }

    @Test fun cancelDelegatesToActiveCall() = runBlocking {
        val blocking = BlockingCallFactory()
        val adapter = OpenAiCompatibleProviderAdapter(blocking, resolver, registry, clock = { 10 })
        val job =
            async(Dispatchers.IO) {
                runCatching {
                    adapter.stream(
                        ModelRequest(
                            "req-cancel",
                            OutboundChannel.LLM_INFERENCE,
                            profile,
                            listOf(userMessage("x")),
                            capability(100),
                            1,
                        ),
                    ).toList()
                }
            }
        assertTrue(blocking.started.await(2, TimeUnit.SECONDS))
        assertTrue(adapter.cancel("req-cancel"))
        assertTrue(blocking.call.cancelled)
        blocking.release.countDown()
        job.await()
        Unit
    }

    private fun capability(expires: Long) = CapabilitySnapshot(
        registry.digest(profile),
        setOf("text"),
        setOf("stream", "tools", "usage", "cancel"),
        204_800,
        2_048,
        0,
        expires,
    )

    private fun userMessage(content: String) = ModelMessage(
        role = "user",
        content = content,
        sensitivity = OutboundSensitivity.PERSONAL,
        purpose = OutboundPurpose.USER_AUTHORED,
        provenance = OutboundProvenance("test_input", "provider-module-test"),
    )

    private fun response(code: Int, body: String, headers: Map<String, String> = emptyMap()): Response {
        val request = Request.Builder().url("https://example.invalid").build()
        val builder = Response.Builder().request(
            request,
        ).protocol(
            Protocol.HTTP_1_1,
        ).code(code).message("test").body(body.toResponseBody("text/event-stream".toMediaType()))
        headers.forEach(builder::header)
        return builder.build()
    }

    private class QueueCallFactory(vararg responses: Response) : Call.Factory {
        private val queue = ArrayDeque(responses.toList())
        val requests = mutableListOf<Request>()
        val calls = mutableListOf<FixedCall>()
        override fun newCall(request: Request): Call {
            requests += request
            return FixedCall(request, queue.removeFirst()).also(calls::add)
        }
    }
    private class FixedCall(private val req: Request, private val response: Response) : Call {
        private var executed = false
        private var cancelled = false
        override fun request() = req
        override fun execute(): Response {
            executed = true
            return response.newBuilder().request(req).build()
        }
        override fun enqueue(responseCallback: Callback) = error("unused")
        override fun cancel() {
            cancelled = true
        }
        override fun isExecuted() = executed
        override fun isCanceled() = cancelled
        override fun timeout() = Timeout.NONE
        override fun clone(): Call = FixedCall(req, response)
    }
    private class ThrowingCallFactory : Call.Factory {
        override fun newCall(request: Request): Call = object : Call {
            override fun request() = request
            override fun execute(): Response = throw SSLPeerUnverifiedException("test certificate detail")
            override fun enqueue(responseCallback: Callback) = error("unused")
            override fun cancel() = Unit
            override fun isExecuted() = true
            override fun isCanceled() = false
            override fun timeout() = Timeout.NONE
            override fun clone(): Call = newCall(request)
        }
    }
    private class BlockingCallFactory : Call.Factory {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        lateinit var call: BlockingCall
        override fun newCall(request: Request): Call = BlockingCall(request, started, release).also { call = it }
    }
    private class BlockingCall(private val req: Request, private val started: CountDownLatch, private val release: CountDownLatch) : Call {
        @Volatile var cancelled = false
        override fun request() = req
        override fun execute(): Response {
            started.countDown()
            release.await(2, TimeUnit.SECONDS)
            if (cancelled) throw java.io.IOException("cancelled")
            return response(200, "data: [DONE]\n")
        }
        override fun enqueue(responseCallback: Callback) = error("unused")
        override fun cancel() {
            cancelled = true
        }
        override fun isExecuted() = true
        override fun isCanceled() = cancelled
        override fun timeout() = Timeout.NONE
        override fun clone(): Call = this
        private fun response(code: Int, body: String) = Response.Builder().request(
            req,
        ).protocol(Protocol.HTTP_1_1).code(code).message("test").body(body.toResponseBody()).build()
    }
}
