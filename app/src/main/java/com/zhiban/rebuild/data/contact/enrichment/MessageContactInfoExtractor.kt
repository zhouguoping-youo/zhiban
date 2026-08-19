package com.zhiban.rebuild.data.contact.enrichment

import com.zhiban.rebuild.data.contact.normalizeContactPhone
import com.zhiban.rebuild.foundation.runSuspendCatching
import com.zhiban.rebuild.provider.CapabilitySnapshot
import com.zhiban.rebuild.provider.ModelEvent
import com.zhiban.rebuild.provider.ModelMessage
import com.zhiban.rebuild.provider.ModelRequest
import com.zhiban.rebuild.provider.OutboundChannel
import com.zhiban.rebuild.provider.OutboundProvenance
import com.zhiban.rebuild.provider.OutboundPurpose
import com.zhiban.rebuild.provider.OutboundSensitivity
import com.zhiban.rebuild.provider.ProviderAdapter
import com.zhiban.rebuild.provider.ProviderProfile
import com.zhiban.rebuild.provider.ProviderProfileStore
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

/** 从消息正文抽取的对方自述身份信息;kind ∈ MessageContactFieldKinds。 */
data class ExtractedContactField(val kind: String, val value: String, val confidence: Double)

/** 抽取能力的最小接口,协调器依赖它;真实现走 LLM,测试可注入固定结果。 */
internal fun interface MessageContactFieldExtraction {
    suspend fun extract(requestId: String, contactName: String, message: String): List<ExtractedContactField>
}

internal object MessageContactFieldKinds {
    const val COMPANY = "COMPANY"
    const val TITLE = "TITLE"
    const val PHONE = "PHONE"
    val supported = setOf(COMPANY, TITLE, PHONE)
}

/**
 * 一条 1:1 消息 → 对方自述身份字段(公司全称/职位/手机号)的一次性 LLM 抽取。
 * 走用户已配置的 provider 同一通道(response_format 约束 JSON 输出,与回复起草同模式);
 * 出云与否由 OutboundDataPolicy 的 allowCloudLlm 统一闸门决定,这里不再重复判断。
 */
@Singleton
internal open class MessageContactInfoExtractor @Inject constructor(private val provider: ProviderAdapter, private val profileStore: ProviderProfileStore) :
    MessageContactFieldExtraction {
    override suspend fun extract(requestId: String, contactName: String, message: String): List<ExtractedContactField> {
        val profile = profileStore.load() ?: return emptyList()
        val capability = runSuspendCatching { provider.probe(profile, requestId) }.getOrNull() ?: return emptyList()
        // 小模型偶发截断/跑题:有界重试,拿不到合法 JSON 就当没抽到(宁缺毋滥,建议卡或跳过都好过写错)。
        repeat(MAX_ATTEMPTS) { attempt ->
            val fields = runSuspendCatching { extractOnce(requestId, contactName, message, profile, capability, attempt) }
                .onFailure { if (it is CancellationException) throw it }
                .getOrNull()
                .orEmpty()
            if (fields.isNotEmpty()) return fields
        }
        return emptyList()
    }

    private suspend fun extractOnce(
        requestId: String,
        contactName: String,
        message: String,
        profile: ProviderProfile,
        capability: CapabilitySnapshot,
        attempt: Int,
    ): List<ExtractedContactField> {
        val output = StringBuilder()
        var final = false
        provider.stream(
            ModelRequest(
                requestId = "$requestId-extract-$attempt",
                channel = OutboundChannel.LLM_INFERENCE,
                profile = profile,
                messages = listOf(
                    ModelMessage(
                        "system",
                        SYSTEM_PROMPT,
                        OutboundSensitivity.PUBLIC,
                        OutboundPurpose.SYSTEM_INSTRUCTION,
                        OutboundProvenance("system_policy", "contact-completion-extract-v1"),
                    ),
                    ModelMessage(
                        "user",
                        "联系人：$contactName\n消息：$message\n请输出抽取结果。",
                        OutboundSensitivity.PERSONAL,
                        OutboundPurpose.AUTO_RETRIEVED,
                        OutboundProvenance("conversation_message", "$requestId-extract-$attempt"),
                    ),
                ),
                capability = capability,
                maxTokens = minOf(MAX_OUTPUT_TOKENS, capability.maxOutputTokens),
                jsonSchema = FIELDS_SCHEMA,
            ),
        ).collect { event ->
            when (event) {
                is ModelEvent.Delta -> if (output.length + event.text.length <= MAX_OUTPUT_CHARS) output.append(event.text)
                is ModelEvent.Final -> final = true
                is ModelEvent.ToolCall -> error("CONTACT_COMPLETION_TOOL_CALL_FORBIDDEN")
                is ModelEvent.Usage -> Unit
            }
        }
        if (!final) return emptyList()
        return parseExtractedFields(output.toString())
    }

    private companion object {
        const val MAX_ATTEMPTS = 2
        const val MAX_OUTPUT_TOKENS = 512
        const val MAX_OUTPUT_CHARS = 2_048
    }
}

/** 容错解析:允许模型加解释文字/围栏,取第一个 {...} 对象;非法字段与非法值全部丢弃。 */
internal fun parseExtractedFields(raw: String): List<ExtractedContactField> {
    val firstBrace = raw.indexOf('{')
    val lastBrace = raw.lastIndexOf('}')
    if (firstBrace < 0 || lastBrace < firstBrace) return emptyList()
    val root = runCatching { Json.parseToJsonElement(raw.substring(firstBrace, lastBrace + 1)).jsonObject }
        .getOrNull() ?: return emptyList()
    val array = runCatching { root["fields"]?.jsonArray }.getOrNull() ?: return emptyList()
    return array.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val kind = obj["kind"]?.jsonPrimitive?.contentOrNull?.trim()?.uppercase()
            ?.takeIf { it in MessageContactFieldKinds.supported } ?: return@mapNotNull null
        val rawValue = obj["value"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotBlank)
            ?: return@mapNotNull null
        val confidence = (obj["confidence"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
            ?: return@mapNotNull null
        val value = when (kind) {
            MessageContactFieldKinds.COMPANY -> rawValue.replace(Regex("\\s+"), " ").take(200)
            MessageContactFieldKinds.TITLE -> rawValue.replace(Regex("\\s+"), " ").take(200)
            MessageContactFieldKinds.PHONE -> normalizeContactPhone(rawValue) ?: return@mapNotNull null
            else -> return@mapNotNull null
        }
        ExtractedContactField(kind = kind, value = value, confidence = confidence.coerceIn(0.0, 1.0))
    }
}

private val SYSTEM_PROMPT = """
你是知伴的联系人资料抽取器。给定联系人名称和一条 1:1 聊天消息，抽取消息中**对方自述**的身份信息。
规则：
- 只抽取消息里明确出现的内容，绝不推断或补全。
- COMPANY=公司/单位全称（如"平凯星辰（北京）科技有限公司"）；TITLE=职位/岗位；PHONE=手机号（11 位，中国大陆）。
- confidence 表示把握：明确自述（如"我是X公司销售"）0.9-1.0；措辞模糊 0.5-0.8。没有把握的字段不要输出。
- 严格按 JSON schema 输出，不要输出任何其他文字。
""".trim()

private val FIELDS_SCHEMA = """
{
  "name": "contact_fields",
  "strict": true,
  "schema": {
    "type": "object",
    "properties": {
      "fields": {
        "type": "array",
        "items": {
          "type": "object",
          "properties": {
            "kind": {"type": "string", "enum": ["COMPANY", "TITLE", "PHONE"]},
            "value": {"type": "string"},
            "confidence": {"type": "number"}
          },
          "required": ["kind", "value", "confidence"],
          "additionalProperties": false
        }
      }
    },
    "required": ["fields"],
    "additionalProperties": false
  }
}
""".trim()
