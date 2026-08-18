package com.zhiban.rebuild.data.completion

import com.zhiban.rebuild.data.contact.ContactEnrichmentCandidateEntity
import com.zhiban.rebuild.data.contact.ContactProfileField
import com.zhiban.rebuild.data.contact.normalizeContactPhone
import com.zhiban.rebuild.runtime.provider.CapabilitySnapshot
import com.zhiban.rebuild.runtime.provider.ModelEvent
import com.zhiban.rebuild.runtime.provider.ModelMessage
import com.zhiban.rebuild.runtime.provider.ModelRequest
import com.zhiban.rebuild.runtime.provider.OutboundChannel
import com.zhiban.rebuild.runtime.provider.OutboundProvenance
import com.zhiban.rebuild.runtime.provider.OutboundPurpose
import com.zhiban.rebuild.runtime.provider.OutboundSensitivity
import com.zhiban.rebuild.runtime.provider.ProviderAdapter
import com.zhiban.rebuild.runtime.provider.ProviderFailure
import com.zhiban.rebuild.runtime.provider.ProviderProfile
import com.zhiban.rebuild.runtime.provider.ProviderProfileStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 一次"请补全资料"回复里抽到的字段。全可空;只填所问字段,绝不顺手收割未索要的。 */
internal data class CompletionExtraction(
    val phone: String? = null,
    val email: String? = null,
    val wechatId: String? = null,
    val company: String? = null,
    val title: String? = null,
    val responsibilities: String? = null,
)

/** 只走本地正则的字段(直接标识符绝不送 LLM——PERSONAL 出站会被脱敏,LLM 也抽不到)。 */
private val DETERMINISTIC_FIELDS = setOf(ContactProfileField.PHONE, ContactProfileField.EMAIL, ContactProfileField.WECHAT)

/** 只走 LLM 的字段(组织/职责类,非 PII)。 */
private val LLM_FIELDS = setOf(ContactProfileField.COMPANY, ContactProfileField.TITLE, ContactProfileField.RESPONSIBILITIES)

private val PHONE_PATTERN = Regex("(?<!\\d)1[3-9][\\d\\s-]{9,11}(?!\\d)")
private val EMAIL_PATTERN = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")

// 微信号无分隔符,裸词太易误判;要求带"微信"提示词才抽。微信号 6-20 位、字母开头、字母数字_-。
private val WECHAT_PATTERN = Regex("微信[号]?\\s*[:：]?\\s*([A-Za-z][A-Za-z0-9_-]{5,19})")

/** 解码所问字段(JSON 数组的 ContactProfileField 名)。畸形/未知名一律忽略,只认合法字段。 */
internal fun decodeRequestedCompletionFields(requestedFieldsJson: String): Set<ContactProfileField> = runCatching {
    Json.parseToJsonElement(requestedFieldsJson).jsonArray
        .mapNotNull { element -> runCatching { ContactProfileField.valueOf(element.jsonPrimitive.content) }.getOrNull() }
        .toSet()
}.getOrDefault(emptySet())

/** 归一化后的大陆手机号(11 位、1 开头),没有则 null。 */
internal fun extractPhoneFromReply(text: String): String? = PHONE_PATTERN.findAll(text)
    .mapNotNull { normalizeContactPhone(it.value) }
    .firstOrNull { it.length == 11 && it.startsWith("1") }

internal fun extractEmailFromReply(text: String): String? = EMAIL_PATTERN.find(text)?.value

internal fun extractWechatIdFromReply(text: String): String? = WECHAT_PATTERN.find(text)?.groupValues?.get(1)

/**
 * 补全回复解析:把联系人回复抽成结构化的 [CompletionExtraction] 并构造待核实候选。与
 * [ContactCompletionOutreachGenerator] 一样不带数据库(纯逻辑、可 JVM 测);落库由协调器负责
 * (仿 generator 起稿 / repository 落库 的职责分层)。
 *
 * 纪律:只解析所问字段(绝不顺手收割未索要的 PII);手机/邮箱/微信号走本地正则(直接标识符不送 LLM,
 * 出站策略本就脱敏);公司/职位/职责才走 LLM(jsonSchema 全可空,没提到就 null)。LLM 拿不到 provider /
 * 输出畸形一律当"没抽到",不抛异常。
 */
@Singleton
internal class ContactCompletionResponseParser @Inject constructor(private val provider: ProviderAdapter, private val profileStore: ProviderProfileStore) {
    /** 解析回复文本,只抽 [request] 所问的字段。 */
    suspend fun extract(request: ContactCompletionRequestEntity, replyText: String): CompletionExtraction {
        val asked = decodeRequestedCompletionFields(request.requestedFieldsJson)
        if (asked.isEmpty() || replyText.isBlank()) return CompletionExtraction()
        val deterministic = CompletionExtraction(
            phone = if (ContactProfileField.PHONE in asked) extractPhoneFromReply(replyText) else null,
            email = if (ContactProfileField.EMAIL in asked) extractEmailFromReply(replyText) else null,
            wechatId = if (ContactProfileField.WECHAT in asked) extractWechatIdFromReply(replyText) else null,
        )
        val llmAsked = asked.intersect(LLM_FIELDS)
        val org = if (llmAsked.isEmpty()) {
            emptyMap()
        } else {
            extractOrganizationViaLlm(replyText, llmAsked, request.requestId)
        }
        return deterministic.copy(
            company = org["company"],
            title = org["title"],
            responsibilities = org["responsibilities"],
        )
    }

    /** 把抽取结果折成 ≤3 条候选(1×通讯方式 + 1×任职 + 1×职责),只含非空字段。 */
    fun buildCompletionCandidates(
        request: ContactCompletionRequestEntity,
        extraction: CompletionExtraction,
        nowEpochMs: Long,
    ): List<ContactEnrichmentCandidateEntity> = buildList {
        communicationJson(extraction)?.let { add(candidate(request, KIND_COMMUNICATION, it, CONFIDENCE_DETERMINISTIC, nowEpochMs)) }
        employmentJson(extraction)?.let { add(candidate(request, KIND_EMPLOYMENT, it, CONFIDENCE_LLM, nowEpochMs)) }
        extraction.responsibilities?.let { add(candidate(request, KIND_RESPONSIBILITIES, responsibilitiesJson(it), CONFIDENCE_LLM, nowEpochMs)) }
    }

    private fun candidate(
        request: ContactCompletionRequestEntity,
        fieldKind: String,
        proposedValueJson: String,
        confidence: Double,
        nowEpochMs: Long,
    ): ContactEnrichmentCandidateEntity {
        // 确定性 candidateId:同一请求同一字段类重复解析靠确定性 PK 去重(协调器 REPLACE 落库,新值覆盖旧值)。
        val candidateId = "cc-${request.requestId}-$fieldKind"
        return ContactEnrichmentCandidateEntity(
            candidateId = candidateId,
            contactId = request.contactId,
            providerId = PROVIDER_ID,
            fieldKind = fieldKind,
            proposedValueJson = proposedValueJson,
            // 前缀 "completion:{requestId}" 供懒对账按请求聚合候选(Phase 7)。
            sourceRef = "completion:${request.requestId}:$fieldKind",
            confidence = confidence,
            status = STATUS_PENDING,
            observedAtEpochMs = nowEpochMs,
            expiresAtEpochMs = nowEpochMs + CANDIDATE_TTL_MS,
            createdAtEpochMs = nowEpochMs,
            updatedAtEpochMs = nowEpochMs,
        )
    }

    private suspend fun extractOrganizationViaLlm(replyText: String, asked: Set<ContactProfileField>, requestId: String): Map<String, String> {
        val profile = profileStore.load() ?: return emptyMap()
        val capability = runCatching { provider.probe(profile, "ccx-$requestId") }.getOrNull() ?: return emptyMap()
        repeat(MAX_LLM_ATTEMPTS) { attempt ->
            val raw = try {
                streamExtraction(buildExtractionRequest(replyText, "$requestId-x$attempt", profile, capability))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                null
            }
            val parsed = raw?.let { parseExtraction(it, asked) }
            if (!parsed.isNullOrEmpty()) return parsed
        }
        return emptyMap()
    }

    private fun buildExtractionRequest(replyText: String, requestId: String, profile: ProviderProfile, capability: CapabilitySnapshot): ModelRequest =
        ModelRequest(
            requestId = requestId,
            channel = OutboundChannel.LLM_INFERENCE,
            profile = profile,
            messages = listOf(
                ModelMessage(
                    "system",
                    EXTRACTION_SYSTEM_PROMPT,
                    OutboundSensitivity.PUBLIC,
                    OutboundPurpose.SYSTEM_INSTRUCTION,
                    OutboundProvenance("system_policy", "completion-extract-v1"),
                ),
                ModelMessage(
                    "user",
                    replyText,
                    OutboundSensitivity.PERSONAL,
                    OutboundPurpose.AUTO_RETRIEVED,
                    OutboundProvenance("completion_reply", requestId),
                ),
            ),
            capability = capability,
            maxTokens = minOf(MAX_OUTPUT_TOKENS, capability.maxOutputTokens),
            jsonSchema = EXTRACTION_SCHEMA,
        )

    private suspend fun streamExtraction(request: ModelRequest): String? {
        val buffer = StringBuilder()
        var finished = false
        provider.stream(request).collect { event ->
            when (event) {
                is ModelEvent.Delta -> {
                    val room = MAX_OUTPUT_CHARS - buffer.length
                    if (room > 0) buffer.append(event.text.take(room))
                }

                is ModelEvent.Final -> finished = true

                is ModelEvent.ToolCall -> throw ProviderFailure("COMPLETION_EXTRACT_TOOL_CALL_FORBIDDEN", retryable = false)

                is ModelEvent.Usage -> Unit
            }
        }
        return buffer.takeIf { finished }?.toString()
    }

    /** 从模型输出抠出 JSON 对象,只保留所问且非空的 company/title/responsibilities。 */
    private fun parseExtraction(raw: String, asked: Set<ContactProfileField>): Map<String, String> {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return emptyMap()
        val obj = runCatching { JSON.parseToJsonElement(raw.substring(start, end + 1)).jsonObject }.getOrNull() ?: return emptyMap()
        return buildMap {
            if (ContactProfileField.COMPANY in asked) putScalar(obj, "company")
            if (ContactProfileField.TITLE in asked) putScalar(obj, "title")
            if (ContactProfileField.RESPONSIBILITIES in asked) putScalar(obj, "responsibilities")
        }
    }

    private fun MutableMap<String, String>.putScalar(obj: JsonObject, key: String) {
        val value = obj[key]?.jsonPrimitive?.contentOrNull?.trim()
            ?.takeIf { it.isNotEmpty() && it.length <= MAX_FIELD_CHARS }
        if (value != null) put(key, value)
    }

    private companion object {
        const val KIND_COMMUNICATION = "COMMUNICATION_METHOD"
        const val KIND_EMPLOYMENT = "EMPLOYMENT"
        const val KIND_RESPONSIBILITIES = "RESPONSIBILITIES"
        const val PROVIDER_ID = "contact-completion-outreach"
        const val STATUS_PENDING = "PENDING"
        const val CONFIDENCE_DETERMINISTIC = 0.9
        const val CONFIDENCE_LLM = 0.7
        const val MAX_LLM_ATTEMPTS = 2
        const val MAX_OUTPUT_TOKENS = 512
        const val MAX_OUTPUT_CHARS = 2_048
        const val MAX_FIELD_CHARS = 500
        const val CANDIDATE_TTL_MS = 30L * 24 * 60 * 60 * 1_000
        val JSON = Json { ignoreUnknownKeys = true }

        val EXTRACTION_SCHEMA = """
{
  "name": "completion_extract",
  "strict": true,
  "schema": {
    "type": "object",
    "properties": {
      "company": {"type": ["string", "null"]},
      "title": {"type": ["string", "null"]},
      "responsibilities": {"type": ["string", "null"]}
    },
    "required": ["company", "title", "responsibilities"],
    "additionalProperties": false
  }
}
""".trim()

        val EXTRACTION_SYSTEM_PROMPT = """
你在帮知伴从一条中文微信回复里抽取某位联系人的组织信息。
只抽取对方在回复里明确提到的：公司全称（company）、职位（title）、职责/负责什么（responsibilities）。
规则：
- 没有明确提到的字段一律填 null，不要猜测或编造。
- 公司要给全称；职责概括对方负责的业务范围。
- 不要抽取电话、邮箱、微信号等联系方式（那些不归你管）。
- 严格按 JSON schema 输出一个对象，不要输出任何其他文字或解释。
""".trim()
    }
}

private fun communicationJson(e: CompletionExtraction): String? {
    val map = buildMap<String, JsonPrimitive> {
        e.phone?.let { put("phone", JsonPrimitive(it)) }
        e.email?.let { put("email", JsonPrimitive(it)) }
        e.wechatId?.let { put("wechatId", JsonPrimitive(it)) }
    }
    return map.takeIf { it.isNotEmpty() }?.let { JsonObject(it).toString() }
}

private fun employmentJson(e: CompletionExtraction): String? {
    val map = buildMap<String, JsonPrimitive> {
        e.company?.let { put("company", JsonPrimitive(it)) }
        e.title?.let { put("title", JsonPrimitive(it)) }
    }
    return map.takeIf { it.isNotEmpty() }?.let { JsonObject(it).toString() }
}

private fun responsibilitiesJson(responsibilities: String): String = JsonObject(mapOf("responsibilities" to JsonPrimitive(responsibilities))).toString()
