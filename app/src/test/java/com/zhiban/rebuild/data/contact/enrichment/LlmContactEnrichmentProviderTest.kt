package com.zhiban.rebuild.data.contact.enrichment

import com.zhiban.rebuild.runtime.provider.CapabilitySnapshot
import com.zhiban.rebuild.runtime.provider.ModelEvent
import com.zhiban.rebuild.runtime.provider.ModelRequest
import com.zhiban.rebuild.runtime.provider.ProviderAdapter
import com.zhiban.rebuild.runtime.provider.ProviderProfile
import com.zhiban.rebuild.runtime.provider.ProviderProfileStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmContactEnrichmentProviderTest {

    @Test
    fun `empty approved fields returns empty without any provider request`() = runBlocking {
        val adapter = RecordingAdapter(reply = "[]")
        val subject = LlmContactEnrichmentProvider(adapter, FixedProfileStore())
        val out = subject.suggest(request(approved = emptySet()))
        assertTrue(out.isEmpty())
        assertEquals(0, adapter.streamCalls)
        assertEquals(0, adapter.probeCalls)
    }

    @Test
    fun `unconfigured profile returns empty without streaming`() = runBlocking {
        val adapter = RecordingAdapter(reply = "[]")
        val subject = LlmContactEnrichmentProvider(adapter, EmptyProfileStore())
        val out = subject.suggest(request())
        assertTrue(out.isEmpty())
        assertEquals(0, adapter.streamCalls)
    }

    @Test
    fun `prompt only mentions approved fields and their hints`() = runBlocking {
        val adapter = RecordingAdapter(reply = "[]")
        val subject = LlmContactEnrichmentProvider(adapter, FixedProfileStore())
        subject.suggest(
            request(
                approved = setOf(ContactEnrichmentField.ORGANIZATION),
                companyHint = "星河科技",
                addressHint = "上海市徐汇区",
            ),
        )
        val userPrompt = adapter.lastRequest!!.messages.first { it.role == "user" }.content
        assertTrue(userPrompt.contains("星河科技"))
        assertTrue(userPrompt.contains("ORGANIZATION"))
        // ADDRESS not approved -> its hint and name must not leak into the prompt.
        assertTrue(!userPrompt.contains("上海市徐汇区"))
        assertTrue(!userPrompt.contains("ADDRESS"))
    }

    @Test
    fun `parses valid suggestions and stamps source and expiry`() = runBlocking {
        val reply = """[{"field":"ORGANIZATION","value":{"company":"星河科技"},"confidence":0.9}]"""
        val adapter = RecordingAdapter(reply = reply)
        val subject = LlmContactEnrichmentProvider(adapter, FixedProfileStore())
        val before = System.currentTimeMillis()
        val out = subject.suggest(request(approved = setOf(ContactEnrichmentField.ORGANIZATION)))
        assertEquals(1, out.size)
        val s = out[0]
        assertEquals(ContactEnrichmentField.ORGANIZATION, s.field)
        assertTrue(s.proposedValueJson.contains("星河科技"))
        assertEquals("llm:stepfun", s.sourceRef)
        assertEquals(0.9, s.confidence, 1e-9)
        assertTrue(s.observedAtEpochMs >= before)
        assertEquals(s.observedAtEpochMs + 7L * 24 * 60 * 60 * 1_000, s.expiresAtEpochMs)
    }

    @Test
    fun `drops invalid entries, unapproved fields and out-of-range confidence`() = runBlocking {
        val reply = """
            [
              {"field":"ORGANIZATION","value":{"company":"A"},"confidence":0.9},
              {"field":"ADDRESS","value":{"formatted":"B"},"confidence":0.9},
              {"field":"ORGANIZATION","value":{"company":"C"},"confidence":0.2},
              {"field":"ORGANIZATION","value":{"company":"D"},"confidence":1.7},
              {"field":"ORGANIZATION","value":{},"confidence":0.9},
              {"field":"NOPE","value":{"x":1},"confidence":0.9},
              {"value":{"company":"E"},"confidence":0.9}
            ]
        """.trimIndent()
        val adapter = RecordingAdapter(reply = reply)
        val subject = LlmContactEnrichmentProvider(adapter, FixedProfileStore())
        val out = subject.suggest(request(approved = setOf(ContactEnrichmentField.ORGANIZATION)))
        assertEquals(1, out.size)
        assertTrue(out[0].proposedValueJson.contains("\"A\""))
    }

    @Test
    fun `tolerates markdown code fences around the array`() = runBlocking {
        val reply = "```json\n[{\"field\":\"EMPLOYMENT\",\"value\":{\"title\":\"采购经理\"},\"confidence\":0.8}]\n```"
        val adapter = RecordingAdapter(reply = reply)
        val subject = LlmContactEnrichmentProvider(adapter, FixedProfileStore())
        val out = subject.suggest(request(approved = setOf(ContactEnrichmentField.EMPLOYMENT)))
        assertEquals(1, out.size)
        assertEquals(ContactEnrichmentField.EMPLOYMENT, out[0].field)
    }

    @Test
    fun `malformed model output is ignored instead of crashing enrichment`() = runBlocking {
        val malformedOutputs = listOf(
            "not-json",
            "{\"field\":\"ORGANIZATION\"}",
            "[{\"field\":{},\"value\":[],\"confidence\":\"high\"}]",
            "[broken]",
        )

        malformedOutputs.forEach { reply ->
            val subject = LlmContactEnrichmentProvider(RecordingAdapter(reply), FixedProfileStore())
            assertTrue(subject.suggest(request()).isEmpty())
        }
    }

    private fun request(
        approved: Set<ContactEnrichmentField> = setOf(ContactEnrichmentField.ORGANIZATION),
        companyHint: String? = null,
        addressHint: String? = null,
    ) = ContactEnrichmentRequest(
        contactId = "contact-1",
        approvedFields = approved,
        displayName = "张三",
        companyHint = companyHint,
        addressHint = addressHint,
    )

    private class RecordingAdapter(private val reply: String) : ProviderAdapter {
        var probeCalls = 0
        var streamCalls = 0
        var lastRequest: ModelRequest? = null

        override suspend fun probe(profile: ProviderProfile): CapabilitySnapshot {
            probeCalls += 1
            return CapabilitySnapshot(
                profileDigest = profile.modelId,
                modalities = setOf("text"),
                features = setOf("stream"),
                maxContextTokens = 8_192,
                maxOutputTokens = 2_048,
                observedAtEpochMs = 0,
                expiresAtEpochMs = Long.MAX_VALUE,
            )
        }

        override fun stream(request: ModelRequest): Flow<ModelEvent> {
            streamCalls += 1
            lastRequest = request
            return flowOf(ModelEvent.Delta(0, reply), ModelEvent.Final("stop"))
        }

        override fun cancel(requestId: String): Boolean = true
    }

    private class FixedProfileStore : ProviderProfileStore {
        override suspend fun load(): ProviderProfile = ProviderProfile("stepfun", "primary", "step-3.5-flash", "stepfun.primary", 1)
        override suspend fun save(profile: ProviderProfile) = Unit
        override suspend fun clear() = Unit
    }

    private class EmptyProfileStore : ProviderProfileStore {
        override suspend fun load(): ProviderProfile? = null
        override suspend fun save(profile: ProviderProfile) = Unit
        override suspend fun clear() = Unit
    }
}
