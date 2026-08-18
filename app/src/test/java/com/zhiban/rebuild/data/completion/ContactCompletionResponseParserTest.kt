package com.zhiban.rebuild.data.completion

import com.zhiban.rebuild.data.contact.ContactProfileField
import com.zhiban.rebuild.provider.CapabilitySnapshot
import com.zhiban.rebuild.provider.ModelEvent
import com.zhiban.rebuild.provider.ModelRequest
import com.zhiban.rebuild.provider.ProviderAdapter
import com.zhiban.rebuild.provider.ProviderProfile
import com.zhiban.rebuild.provider.ProviderProfileStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 纯 JVM 测 [ContactCompletionResponseParser]:正则抽取(手机/邮箱/微信号)、"只解析所问字段"纪律、
 * LLM 组织抽取各形态(干净对象/markdown 包裹/畸形重试/无 provider)、候选构造(分组/确定性 id/前缀)。
 * 无需真机或 API key——LLM 由 fake adapter 驱动。
 */
class ContactCompletionResponseParserTest {

    @Test fun decodeValidFields() {
        assertEquals(
            setOf(ContactProfileField.PHONE, ContactProfileField.COMPANY),
            decodeRequestedCompletionFields("""["PHONE","COMPANY"]"""),
        )
    }

    @Test fun decodeIgnoresUnknownNamesAndMalformedJson() {
        assertEquals(setOf(ContactProfileField.EMAIL), decodeRequestedCompletionFields("""["EMAIL","NOPE"]"""))
        assertTrue(decodeRequestedCompletionFields("not json").isEmpty())
        assertTrue(decodeRequestedCompletionFields("").isEmpty())
    }

    @Test fun extractsMainlandMobile() {
        assertEquals("13800138000", extractPhoneFromReply("我电话13800138000，你记下"))
    }

    @Test fun normalizesPlus86AndSpacedMobile() {
        assertEquals("13800138000", extractPhoneFromReply("手机号是 +86 138 0013 8000"))
    }

    @Test fun rejectsShortOrNonMobileDigitRuns() {
        assertNull(extractPhoneFromReply("工号 1234567，分机 890"))
    }

    @Test fun extractsEmail() {
        assertEquals("zhou.gp@example.com", extractEmailFromReply("邮箱 zhou.gp@example.com 哈"))
    }

    @Test fun extractsWechatIdWithCue() {
        assertEquals("zhangsan_2024", extractWechatIdFromReply("我微信号：zhangsan_2024"))
        assertEquals("wx-abc123", extractWechatIdFromReply("微信 wx-abc123"))
    }

    @Test fun ignoresBareTokenWithoutWechatCue() {
        assertNull(extractWechatIdFromReply("随便一个词 abcdefg 而已"))
    }

    @Test fun extractsOnlyAskedDeterministicFields() = runBlocking {
        val adapter = FakeAdapter("""{"company":"不该被问"}""")
        val parser = ContactCompletionResponseParser(adapter, FakeProfileStore(DUMMY_PROFILE))
        // 只问 PHONE:回复里的邮箱不应被顺手收割,LLM 也不该被调用。
        val result = parser.extract(request(listOf(ContactProfileField.PHONE)), "电话13800138000，邮箱a@b.com")
        assertEquals("13800138000", result.phone)
        assertNull(result.email)
        assertNull(result.company)
        assertEquals(0, adapter.streamCalls)
    }

    @Test fun extractsOrganizationViaLlm() = runBlocking {
        val parser = parserReturning("""{"company":"星河科技有限公司","title":"采购经理","responsibilities":"华南区采购"}""")
        val asked = listOf(ContactProfileField.COMPANY, ContactProfileField.TITLE, ContactProfileField.RESPONSIBILITIES)
        val result = parser.extract(request(asked), "我在星河科技做采购经理，负责华南区采购")
        assertEquals("星河科技有限公司", result.company)
        assertEquals("采购经理", result.title)
        assertEquals("华南区采购", result.responsibilities)
        assertNull(result.phone)
    }

    @Test fun llmMarkdownFencedObjectParses() = runBlocking {
        val parser = parserReturning("```json\n{\"company\":\"星河科技\",\"title\":null,\"responsibilities\":null}\n```")
        val result = parser.extract(request(listOf(ContactProfileField.COMPANY)), "我在星河科技")
        assertEquals("星河科技", result.company)
        assertNull(result.title)
    }

    @Test fun llmOnlyReturnsAskedFields() = runBlocking {
        // 模型多给了 title,但只问了 COMPANY——多给的丢弃。
        val parser = parserReturning("""{"company":"星河科技","title":"采购经理","responsibilities":null}""")
        val result = parser.extract(request(listOf(ContactProfileField.COMPANY)), "我在星河科技")
        assertEquals("星河科技", result.company)
        assertNull(result.title)
    }

    @Test fun llmMalformedOutputIsRetriedThenEmpty() = runBlocking {
        val adapter = FakeAdapter("no json at all")
        val parser = ContactCompletionResponseParser(adapter, FakeProfileStore(DUMMY_PROFILE))
        val result = parser.extract(request(listOf(ContactProfileField.COMPANY)), "我在星河科技")
        assertNull(result.company)
        assertEquals(2, adapter.streamCalls) // 有界重试×2
    }

    @Test fun llmMissingProfileYieldsEmpty() = runBlocking {
        val parser = ContactCompletionResponseParser(FakeAdapter("""{"company":"X"}"""), FakeProfileStore(null))
        val result = parser.extract(request(listOf(ContactProfileField.COMPANY)), "我在星河科技")
        assertNull(result.company)
    }

    @Test fun emptyAskedOrBlankReplyYieldsEmptyExtraction() = runBlocking {
        val parser = parserReturning("""{"company":"X"}""")
        assertEquals(CompletionExtraction(), parser.extract(request(emptyList()), "我在星河科技"))
        assertEquals(
            CompletionExtraction(),
            parser.extract(request(listOf(ContactProfileField.COMPANY)), "   "),
        )
    }

    @Test fun buildsCommunicationCandidateFromDeterministic() = runBlocking {
        val parser = parserReturning(null)
        val extraction = CompletionExtraction(phone = "13800138000", email = "a@b.com")
        val candidates = parser.buildCompletionCandidates(request(listOf(ContactProfileField.PHONE)), extraction, NOW)
        assertEquals(1, candidates.size)
        val c = candidates.first()
        assertEquals("COMMUNICATION_METHOD", c.fieldKind)
        assertEquals("cc-ccr-test-COMMUNICATION_METHOD", c.candidateId)
        assertEquals("contact-completion-outreach", c.providerId)
        assertEquals("completion:ccr-test:COMMUNICATION_METHOD", c.sourceRef)
        assertEquals("PENDING", c.status)
        assertEquals(0.9, c.confidence, 0.001)
        val json = Json.parseToJsonElement(c.proposedValueJson).jsonObject
        assertEquals("13800138000", json["phone"]!!.jsonPrimitive.content)
        assertEquals("a@b.com", json["email"]!!.jsonPrimitive.content)
    }

    @Test fun buildsEmploymentAndResponsibilitiesCandidates() = runBlocking {
        val parser = parserReturning(null)
        val extraction = CompletionExtraction(company = "星河科技", title = "采购经理", responsibilities = "华南采购")
        val candidates = parser.buildCompletionCandidates(request(emptyList()), extraction, NOW)
        assertEquals(2, candidates.size)
        val employment = candidates.first { it.fieldKind == "EMPLOYMENT" }
        val responsibilities = candidates.first { it.fieldKind == "RESPONSIBILITIES" }
        assertEquals(0.7, employment.confidence, 0.001)
        val empJson = Json.parseToJsonElement(employment.proposedValueJson).jsonObject
        assertEquals("星河科技", empJson["company"]!!.jsonPrimitive.content)
        assertEquals("采购经理", empJson["title"]!!.jsonPrimitive.content)
        val respJson = Json.parseToJsonElement(responsibilities.proposedValueJson).jsonObject
        assertEquals("华南采购", respJson["responsibilities"]!!.jsonPrimitive.content)
    }

    @Test fun buildsNoCandidatesWhenExtractionEmpty() = runBlocking {
        val parser = parserReturning(null)
        assertTrue(parser.buildCompletionCandidates(request(emptyList()), CompletionExtraction(), NOW).isEmpty())
    }

    private fun parserReturning(rawModelOutput: String?, emitFinal: Boolean = true) =
        ContactCompletionResponseParser(FakeAdapter(rawModelOutput, emitFinal), FakeProfileStore(DUMMY_PROFILE))

    private fun request(fields: List<ContactProfileField>): ContactCompletionRequestEntity {
        val json = fields.joinToString(",", "[", "]") { "\"${it.name}\"" }
        return ContactCompletionRequestEntity(
            requestId = "ccr-test",
            contactId = "c1",
            requestedFieldsJson = json,
            draftText = "方便补充下资料吗",
            status = ContactCompletionStatus.AWAITING_REPLY,
            createdAtEpochMs = NOW,
            expiresAtEpochMs = NOW + 1_000,
            updatedAtEpochMs = NOW,
        )
    }

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
        const val NOW = 1_000_000L
        val DUMMY_PROFILE = ProviderProfile("stepfun", "stepfun-chat", "step-1", "stepfun.primary", 1)
    }
}
