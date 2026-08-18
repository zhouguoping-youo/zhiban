package com.zhiban.rebuild.data.completion

import com.zhiban.rebuild.data.contact.ContactProfileField
import com.zhiban.rebuild.runtime.provider.CapabilitySnapshot
import com.zhiban.rebuild.runtime.provider.ModelEvent
import com.zhiban.rebuild.runtime.provider.ModelRequest
import com.zhiban.rebuild.runtime.provider.ProviderAdapter
import com.zhiban.rebuild.runtime.provider.ProviderProfile
import com.zhiban.rebuild.runtime.provider.ProviderProfileStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用 fake adapter 驱动真实 [ContactCompletionOutreachGenerator.generateDraft]，覆盖草稿解析各形态
 * （干净对象/markdown 围栏/散文包裹/无 Final）、PII 过滤与有界重试。无需真机或 API key。
 */
class ContactCompletionOutreachGeneratorTest {

    private val askPhoneEmail = listOf(ContactProfileField.PHONE, ContactProfileField.EMAIL)

    @Test fun cleanObjectDraftParses() = runBlocking {
        val draft = generate("""{"draft": "张三你好，方便把你的手机号发我一下吗？"}""")
        assertEquals("张三你好，方便把你的手机号发我一下吗？", draft)
    }

    @Test fun markdownFencedObjectParses() = runBlocking {
        val draft = generate("```json\n{\"draft\": \"方便发我下邮箱吗\"}\n```")
        assertEquals("方便发我下邮箱吗", draft)
    }

    @Test fun proseAroundObjectIsStripped() = runBlocking {
        val draft = generate("好的：\n{\"draft\": \"方便发我下邮箱吗\"}\n望满意。")
        assertEquals("方便发我下邮箱吗", draft)
    }

    @Test fun missingFinalEventYieldsNull() = runBlocking {
        assertNull(generate("""{"draft": "方便发我下邮箱吗"}""", emitFinal = false))
    }

    @Test fun missingProfileYieldsNull() = runBlocking {
        val generator = ContactCompletionOutreachGenerator(FakeAdapter("""{"draft":"x"}"""), FakeProfileStore(null))
        assertNull(generator.generateDraft("张三", askPhoneEmail, null, "req"))
    }

    @Test fun emptyFieldsYieldNullWithoutCallingProvider() = runBlocking {
        val adapter = FakeAdapter("""{"draft":"x"}""")
        ContactCompletionOutreachGenerator(adapter, FakeProfileStore(DUMMY_PROFILE))
            .generateDraft("张三", emptyList(), null, "req")
        assertEquals(0, adapter.streamCalls)
    }

    @Test fun draftContainingDirectIdentifierIsDropped() = runBlocking {
        // 询问类草稿不应含任何直接标识符;含手机号即被 PII 过滤拦下。
        assertNull(generate("""{"draft": "我电话13800138000，你的是多少"}"""))
    }

    @Test fun overlongDraftIsDropped() = runBlocking {
        val long = "好".repeat(200)
        assertNull(generate("""{"draft": "$long"}"""))
    }

    @Test fun malformedOutputIsRetriedThenNull() = runBlocking {
        val adapter = FakeAdapter("no json here at all")
        val draft = ContactCompletionOutreachGenerator(adapter, FakeProfileStore(DUMMY_PROFILE))
            .generateDraft("张三", askPhoneEmail, null, "req")
        assertNull(draft)
        assertEquals(2, adapter.streamCalls)
    }

    @Test fun requestsStructuredOutputSchema() = runBlocking {
        val adapter = FakeAdapter("""{"draft":"方便发我下邮箱吗"}""")
        ContactCompletionOutreachGenerator(adapter, FakeProfileStore(DUMMY_PROFILE))
            .generateDraft("张三", askPhoneEmail, null, "req")
        val schema = adapter.lastRequest?.jsonSchema
        assertTrue(schema != null && schema.contains("completion_outreach") && schema.contains("\"draft\""))
    }

    @Test fun selectFieldsToAskRespectsPriorityAndCap() {
        // 优先级 PHONE>EMAIL>COMPANY>TITLE>RESPONSIBILITIES>WECHAT>NAME,且至多 3 个。
        assertEquals(
            listOf(ContactProfileField.PHONE, ContactProfileField.EMAIL, ContactProfileField.COMPANY),
            selectCompletionFieldsToAsk(ContactProfileField.entries.toList()),
        )
    }

    @Test fun selectFieldsToAskPicksHighestPriorityFromSubset() {
        assertEquals(
            listOf(ContactProfileField.PHONE, ContactProfileField.COMPANY, ContactProfileField.WECHAT),
            selectCompletionFieldsToAsk(listOf(ContactProfileField.NAME, ContactProfileField.WECHAT, ContactProfileField.COMPANY, ContactProfileField.PHONE)),
        )
        assertTrue(selectCompletionFieldsToAsk(emptyList()).isEmpty())
    }

    private suspend fun generate(rawModelOutput: String, emitFinal: Boolean = true): String? =
        ContactCompletionOutreachGenerator(FakeAdapter(rawModelOutput, emitFinal), FakeProfileStore(DUMMY_PROFILE))
            .generateDraft("张三", askPhoneEmail, null, "req")

    private class FakeProfileStore(private val profile: ProviderProfile?) : ProviderProfileStore {
        override suspend fun load(): ProviderProfile? = profile
        override suspend fun save(profile: ProviderProfile) = Unit
        override suspend fun clear() = Unit
    }

    private class FakeAdapter(private val rawText: String?, private val emitFinal: Boolean = true) : ProviderAdapter {
        var lastRequest: ModelRequest? = null
            private set
        var streamCalls = 0
            private set

        override suspend fun probe(profile: ProviderProfile): CapabilitySnapshot =
            CapabilitySnapshot("digest", setOf("TEXT"), setOf("CHAT"), 8_000, 2_048, 0L, Long.MAX_VALUE)

        override fun stream(request: ModelRequest): Flow<ModelEvent> {
            lastRequest = request
            streamCalls++
            return flow {
                rawText?.chunked(7)?.forEachIndexed { index, chunk -> emit(ModelEvent.Delta(index.toLong(), chunk)) }
                if (emitFinal) emit(ModelEvent.Final("stop"))
            }
        }

        override fun cancel(requestId: String): Boolean = true
    }

    private companion object {
        val DUMMY_PROFILE = ProviderProfile("stepfun", "stepfun-chat", "step-1", "stepfun.primary", 1)
    }
}
