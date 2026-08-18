package com.zhiban.rebuild.data.reply

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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives the REAL [ReplyDraftGenerator.generateDrafts] + parse logic against realistic provider output
 * shapes (clean array, markdown-fenced, prose-wrapped, object-form, junk) via a fake adapter. This is the
 * reply-specific risk a fake-coordinator test can't cover; the provider plumbing itself is shared with the
 * already-proven contact-enrichment path. No device or API key needed.
 */
class ReplyDraftGeneratorTest {

    private val context = ReplyDraftContext(
        requestId = "req-1",
        contactName = "张三",
        contactSummary = "星河科技 / 采购经理",
        incomingMessage = "明天上午的合同能发我一份吗？",
        thread = listOf(ReplyThreadMessage(outgoing = false, text = "合同我看过了")),
    )

    @Test fun cleanJsonArrayParsesToDrafts() = runBlocking {
        val drafts = generate("""["好的张总，明早十点前发您邮箱","收到，明天上午给您回复","没问题，明早发你"]""")
        assertEquals(3, drafts.size)
        assertEquals("好的张总，明早十点前发您邮箱", drafts[0])
    }

    @Test fun markdownFencedJsonParses() = runBlocking {
        val drafts = generate("```json\n[\"好的，明早发你\",\"收到，明天上午回复\"]\n```")
        assertEquals(listOf("好的，明早发你", "收到，明天上午回复"), drafts)
    }

    @Test fun proseAroundArrayIsStripped() = runBlocking {
        val drafts = generate("好的，以下是三条草稿：\n[\"明早发您\",\"收到\"]\n希望对您有帮助。")
        assertEquals(listOf("明早发您", "收到"), drafts)
    }

    @Test fun objectFormParsesViaBracketExtraction() = runBlocking {
        val drafts = generate("""{"drafts": ["明早十点前发您", "收到，明天回复"]}""")
        assertEquals(listOf("明早十点前发您", "收到，明天回复"), drafts)
    }

    @Test fun pureProseWithoutArrayYieldsEmpty() = runBlocking {
        val drafts = generate("好的，我明天上午把合同发给您。")
        assertTrue(drafts.isEmpty())
    }

    @Test fun overlongDraftIsDropped() = runBlocking {
        val long = "好".repeat(200)
        val drafts = generate("""["$long","收到"]""")
        assertEquals(listOf("收到"), drafts)
    }

    @Test fun duplicateDraftsAreDistinct() = runBlocking {
        val drafts = generate("""["收到","收到","好的"]""")
        assertEquals(listOf("收到", "好的"), drafts)
    }

    @Test fun missingFinalEventYieldsEmpty() = runBlocking {
        val drafts = generate("""["收到"]""", emitFinal = false)
        assertTrue(drafts.isEmpty())
    }

    @Test fun missingProfileYieldsEmpty() = runBlocking {
        val generator = ReplyDraftGenerator(FakeAdapter("""["收到"]"""), FakeProfileStore(null))
        assertTrue(generator.generateDrafts(context).isEmpty())
    }

    @Test fun requestsStructuredOutputSchema() = runBlocking {
        val adapter = FakeAdapter("""["好的","收到"]""")
        ReplyDraftGenerator(adapter, FakeProfileStore(DUMMY_PROFILE)).generateDrafts(context)
        val schema = adapter.lastRequest?.jsonSchema
        // The reliability fix hinges on constraining the model to a JSON object; lock the wiring in.
        assertTrue(schema != null && schema.contains("reply_drafts") && schema.contains("\"drafts\""))
    }

    @Test fun malformedBatchIsRetried() = runBlocking {
        val adapter = FakeAdapter("no json here at all")
        ReplyDraftGenerator(adapter, FakeProfileStore(DUMMY_PROFILE)).generateDrafts(context)
        // First attempt parses empty -> a second attempt is made before giving up.
        assertEquals(2, adapter.streamCalls)
    }

    private suspend fun generate(rawModelOutput: String, emitFinal: Boolean = true): List<String> =
        ReplyDraftGenerator(FakeAdapter(rawModelOutput, emitFinal), FakeProfileStore(DUMMY_PROFILE)).generateDrafts(context)

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
