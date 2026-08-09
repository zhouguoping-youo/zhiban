package com.zhiban.rebuild.data.contact.enrichment

import com.zhiban.rebuild.runtime.provider.ModelEvent
import com.zhiban.rebuild.runtime.provider.ModelMessage
import com.zhiban.rebuild.runtime.provider.ModelRequest
import com.zhiban.rebuild.runtime.provider.OutboundChannel
import com.zhiban.rebuild.runtime.provider.OutboundProvenance
import com.zhiban.rebuild.runtime.provider.OutboundPurpose
import com.zhiban.rebuild.runtime.provider.OutboundSensitivity
import com.zhiban.rebuild.runtime.provider.ProviderAdapter
import com.zhiban.rebuild.runtime.provider.ProviderProfileStore
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * StepFun-backed [ContactEnrichmentProvider]. Known contact info leaves the device automatically
 * (not user-authored), so the payload is classified AUTO_RETRIEVED + PERSONAL: the fail-closed
 * OutboundDataPolicy masks phone/email/id fragments before they reach the provider. That is
 * intentional — the model only infers organization/employment/address structure from the name and
 * hints, it never needs raw identifiers. Nothing here is ever classified SENSITIVE.
 *
 * Suggestions are evidence only; the user confirms before any contact profile write.
 */
class LlmContactEnrichmentProvider(private val provider: ProviderAdapter, private val profileStore: ProviderProfileStore) : ContactEnrichmentProvider {

    override val providerId: String = "stepfun-llm"
    override val supportedFields: Set<ContactEnrichmentField> = setOf(
        ContactEnrichmentField.ORGANIZATION,
        ContactEnrichmentField.EMPLOYMENT,
        ContactEnrichmentField.ADDRESS,
        ContactEnrichmentField.COMMUNICATION_METHOD,
    )

    override suspend fun suggest(request: ContactEnrichmentRequest): List<ContactEnrichmentSuggestion> {
        val approved = request.approvedFields.intersect(supportedFields)
        if (approved.isEmpty()) return emptyList()

        val now = System.currentTimeMillis()
        val expiresAt = now + SUGGESTION_TTL_MS
        val prompt = buildPrompt(request, approved)

        val profile = profileStore.load() ?: return emptyList()
        val capability = provider.probe(profile, request.contactId)

        val output = StringBuilder()
        var final = false
        provider.stream(
            ModelRequest(
                requestId = "enrich-${request.contactId}",
                channel = OutboundChannel.LLM_INFERENCE,
                profile = profile,
                messages = listOf(
                    ModelMessage(
                        "system",
                        SYSTEM_PROMPT,
                        OutboundSensitivity.PUBLIC,
                        OutboundPurpose.SYSTEM_INSTRUCTION,
                        OutboundProvenance("system_policy", "contact-enrichment-v1"),
                    ),
                    ModelMessage(
                        "user",
                        prompt,
                        OutboundSensitivity.PERSONAL,
                        OutboundPurpose.AUTO_RETRIEVED,
                        OutboundProvenance("contact_record", request.contactId),
                    ),
                ),
                capability = capability,
                maxTokens = minOf(MAX_OUTPUT_TOKENS, capability.maxOutputTokens),
            ),
        ).collect { event ->
            when (event) {
                is ModelEvent.Delta ->
                    if (output.length + event.text.length <= MAX_OUTPUT_CHARS) {
                        output.append(event.text)
                    }

                is ModelEvent.Final -> final = true

                is ModelEvent.ToolCall -> error("ENRICHMENT_TOOL_CALL_FORBIDDEN")

                is ModelEvent.Usage -> Unit
            }
        }
        if (!final) error("ENRICHMENT_STREAM_INCOMPLETE")

        return parseSuggestions(output.toString(), approved, now, expiresAt)
    }

    /** Builds the prompt using only the approved fields; never leaks unapproved contact data. */
    private fun buildPrompt(request: ContactEnrichmentRequest, approved: Set<ContactEnrichmentField>): String {
        val lines = mutableListOf<String>()
        lines += "请基于下列已知信息，推测该联系人可能缺失的字段。只推测信息充分的字段，拿不准就不要给出。"
        request.displayName?.takeIf { it.isNotBlank() }?.let { lines += "姓名：$it" }
        if (ContactEnrichmentField.ORGANIZATION in approved ||
            ContactEnrichmentField.EMPLOYMENT in approved
        ) {
            request.companyHint?.takeIf { it.isNotBlank() }?.let { lines += "公司线索：$it" }
        }
        if (ContactEnrichmentField.ADDRESS in approved) {
            request.addressHint?.takeIf { it.isNotBlank() }?.let { lines += "地址线索：$it" }
        }
        lines += "需要推测的字段：${approved.joinToString("、") { it.wireName() }}"
        return lines.joinToString("\n")
    }

    /** Parses the model JSON array, keeping only approved fields with valid shape and confidence. */
    private fun parseSuggestions(raw: String, approved: Set<ContactEnrichmentField>, now: Long, expiresAt: Long): List<ContactEnrichmentSuggestion> {
        val firstBracket = raw.indexOf('[')
        val lastBracket = raw.lastIndexOf(']')
        if (firstBracket < 0 || lastBracket < firstBracket) return emptyList()
        val array = runCatching { json.parseToJsonElement(raw.substring(firstBracket, lastBracket + 1)).jsonArray }
            .getOrNull() ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val field = (obj["field"] as? JsonPrimitive)?.content?.toField() ?: return@mapNotNull null
            if (field !in approved) return@mapNotNull null
            val value = obj["value"] as? JsonObject ?: return@mapNotNull null
            if (value.isEmpty()) return@mapNotNull null
            val confidence = (obj["confidence"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            if (confidence < MIN_CONFIDENCE || confidence > 1.0) return@mapNotNull null
            ContactEnrichmentSuggestion(
                field = field,
                proposedValueJson = value.toString(),
                sourceRef = SOURCE_REF,
                confidence = confidence,
                observedAtEpochMs = now,
                expiresAtEpochMs = expiresAt,
            )
        }
    }

    private companion object {
        const val MIN_CONFIDENCE = 0.5
        const val MAX_OUTPUT_CHARS = 8_192
        const val MAX_OUTPUT_TOKENS = 1_024
        const val SUGGESTION_TTL_MS = 7L * 24 * 60 * 60 * 1_000
        const val SOURCE_REF = "llm:stepfun"

        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

        val SYSTEM_PROMPT = """
你是联系人资料补全助手。基于给出的已知信息，推测联系人可能缺失的字段（单位、职位、地址、联系方式）。
只推测信息充分的字段；拿不准的字段不要输出。不要编造具体电话/邮箱等标识符，联系方式字段只给出可推断的结构性内容。
只输出一个 JSON 数组，不要输出任何其他文字、解释或 Markdown 代码块。数组元素形如：
[{"field":"ORGANIZATION","value":{"company":"…"},"confidence":0.0}]
field 只能是 ORGANIZATION、EMPLOYMENT、ADDRESS、COMMUNICATION_METHOD 之一；value 是该字段的 JSON 对象；confidence 在 0 到 1 之间。
没有可推测的字段时输出空数组 []。
""".trim()
    }
}

private fun ContactEnrichmentField.wireName(): String = name

private fun String.toField(): ContactEnrichmentField? = runCatching { ContactEnrichmentField.valueOf(this) }.getOrNull()
