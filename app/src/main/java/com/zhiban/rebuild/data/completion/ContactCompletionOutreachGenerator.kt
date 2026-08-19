package com.zhiban.rebuild.data.completion

import com.zhiban.rebuild.data.contact.ContactProfileField
import com.zhiban.rebuild.data.notification.SensitiveMessageFilter
import com.zhiban.rebuild.foundation.runSuspendCatching
import com.zhiban.rebuild.provider.CapabilitySnapshot
import com.zhiban.rebuild.provider.ModelEvent
import com.zhiban.rebuild.provider.ModelMessage
import com.zhiban.rebuild.provider.ModelRequest
import com.zhiban.rebuild.provider.OutboundChannel
import com.zhiban.rebuild.provider.OutboundPiiDetector
import com.zhiban.rebuild.provider.OutboundProvenance
import com.zhiban.rebuild.provider.OutboundPurpose
import com.zhiban.rebuild.provider.OutboundSensitivity
import com.zhiban.rebuild.provider.ProviderAdapter
import com.zhiban.rebuild.provider.ProviderFailure
import com.zhiban.rebuild.provider.ProviderProfile
import com.zhiban.rebuild.provider.ProviderProfileStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 一轮最多追问的字段数——别一次拷问对方。 */
private const val MAX_COMPLETION_ASK_FIELDS = 3

/** 追问优先级：先要对方好答、对 CRM 最有价值的（联系方式>组织>职责>微信号>姓名）。 */
private val COMPLETION_ASK_PRIORITY = listOf(
    ContactProfileField.PHONE,
    ContactProfileField.EMAIL,
    ContactProfileField.COMPANY,
    ContactProfileField.TITLE,
    ContactProfileField.RESPONSIBILITIES,
    ContactProfileField.WECHAT,
    ContactProfileField.NAME,
)

/** 从缺失字段里挑出本轮要问的：按优先级、至多 [cap] 个。返回顺序即 prompt/落库所用的字段顺序。 */
internal fun selectCompletionFieldsToAsk(missing: List<ContactProfileField>, cap: Int = MAX_COMPLETION_ASK_FIELDS): List<ContactProfileField> =
    COMPLETION_ASK_PRIORITY.filter(missing::contains).take(cap)

/**
 * 补全触达起草：为"请对方补全资料"的微信消息起草一条简短文案。半自动闭环里知伴只起草、绝不代发。
 *
 * 与 [com.zhiban.rebuild.data.reply.ReplyDraftGenerator] 共用用户已配置的 provider（LLM_INFERENCE 同一通道与
 * 密钥策略），但 prompt 只含**字段标签 + 联系人名 + 已有业务背景**，绝不带其他联系人数据或用户本人资料。user 消息
 * PERSONAL+AUTO_RETRIEVED（出站策略会脱敏手机/邮箱/证件号——但起草本就不该产出这些，故草稿过 PII 过滤兜底）。
 * 输出经 response_format 约束为 {"draft": "..."}，小模型不易跑偏成散文。
 */
@Singleton
internal open class ContactCompletionOutreachGenerator @Inject constructor(
    private val provider: ProviderAdapter,
    private val profileStore: ProviderProfileStore,
) {

    /**
     * 为 [fields]（应已过 [selectCompletionFieldsToAsk]）起草一条询问文案。
     * 拿不到 provider / 草稿畸形 / 含 PII 或敏感词，一律返回 null（不往 UI 抛异常）。
     */
    open suspend fun generateDraft(contactName: String, fields: List<ContactProfileField>, businessContext: String?, requestKey: String): String? {
        if (fields.isEmpty()) return null
        val profile = profileStore.load() ?: return null
        val capability = runSuspendCatching { provider.probe(profile, requestKey) }.getOrNull() ?: return null
        repeat(MAX_ATTEMPTS) { attempt ->
            val draft = try {
                draftAttempt(contactName, fields, businessContext, "$requestKey-$attempt", profile, capability)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                null
            }
            if (!draft.isNullOrBlank()) return draft
        }
        return null
    }

    private suspend fun draftAttempt(
        contactName: String,
        fields: List<ContactProfileField>,
        businessContext: String?,
        attemptKey: String,
        profile: ProviderProfile,
        capability: CapabilitySnapshot,
    ): String? {
        val raw = streamDraft(buildRequest(contactName, fields, businessContext, attemptKey, profile, capability))
        val candidate = raw?.let(::extractDraft) ?: return null
        // 询问类草稿不应包含任何直接标识符/敏感词（手机号、邮箱、验证码……），命中即弃。
        return candidate.takeUnless(::unsafeDraft)
    }

    private fun buildRequest(
        contactName: String,
        fields: List<ContactProfileField>,
        businessContext: String?,
        requestId: String,
        profile: ProviderProfile,
        capability: CapabilitySnapshot,
    ): ModelRequest = ModelRequest(
        requestId = requestId,
        channel = OutboundChannel.LLM_INFERENCE,
        profile = profile,
        messages = listOf(
            ModelMessage(
                "system",
                SYSTEM_PROMPT,
                OutboundSensitivity.PUBLIC,
                OutboundPurpose.SYSTEM_INSTRUCTION,
                OutboundProvenance("system_policy", "completion-outreach-v1"),
            ),
            ModelMessage(
                "user",
                userPrompt(contactName, fields, businessContext),
                OutboundSensitivity.PERSONAL,
                OutboundPurpose.AUTO_RETRIEVED,
                OutboundProvenance("completion_target", requestId),
            ),
        ),
        capability = capability,
        maxTokens = minOf(MAX_OUTPUT_TOKENS, capability.maxOutputTokens),
        jsonSchema = DRAFT_SCHEMA,
    )

    private suspend fun streamDraft(request: ModelRequest): String? {
        val chunks = StringBuilder()
        var completed = false
        provider.stream(request).collect { event ->
            when (event) {
                is ModelEvent.Delta -> {
                    val remaining = MAX_OUTPUT_CHARS - chunks.length
                    if (remaining > 0) chunks.append(event.text.take(remaining))
                }

                is ModelEvent.Final -> completed = true

                is ModelEvent.ToolCall -> throw ProviderFailure("OUTREACH_TOOL_CALL_FORBIDDEN", retryable = false)

                is ModelEvent.Usage -> Unit
            }
        }
        return if (completed) chunks.toString() else null
    }

    private fun userPrompt(contactName: String, fields: List<ContactProfileField>, businessContext: String?): String {
        val asked = fields.joinToString("、") { it.label }
        return buildString {
            append("联系人：").append(contactName).append('\n')
            if (!businessContext.isNullOrBlank()) append("已知背景：").append(businessContext).append('\n')
            append("想请对方补充：").append(asked).append('\n')
            append("请起草一条简短礼貌的微信消息向他询问，填入 draft 字段。")
        }
    }

    private fun extractDraft(raw: String): String? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val obj = runCatching { json.parseToJsonElement(raw.substring(start, end + 1)).jsonObject }.getOrNull() ?: return null
        val draft = (obj["draft"] as? JsonPrimitive)?.content ?: return null
        return draft.trim().takeIf { it.length in 1..MAX_DRAFT_CHARS }
    }

    private fun unsafeDraft(draft: String): Boolean = SensitiveMessageFilter.shouldDrop(draft) || OutboundPiiDetector.containsDirectIdentifier(draft)

    private companion object {
        const val MAX_ATTEMPTS = 2
        const val MAX_DRAFT_CHARS = 120
        const val MAX_OUTPUT_CHARS = 2_048
        const val MAX_OUTPUT_TOKENS = 512
        val json = Json { ignoreUnknownKeys = true }

        // 结构化输出契约:强制输出单个 JSON 对象 {"draft": "..."},小模型不易跑偏成散文。
        val DRAFT_SCHEMA = """
{
  "name": "completion_outreach",
  "strict": true,
  "schema": {
    "type": "object",
    "properties": {"draft": {"type": "string", "maxLength": 120}},
    "required": ["draft"],
    "additionalProperties": false
  }
}
""".trim()

        val SYSTEM_PROMPT = """
你是知伴的联系人资料补全助手，替用户起草一条发给某位联系人的中文微信消息，礼貌地向对方索要缺失的资料。
要求：
- 只起草一条，不超过 120 字，口语化、自然、不失礼貌。
- 直接向对方本人询问，别一次问太多，别像盘问。
- 不要包含任何人的电话、邮箱、身份证号、验证码、链接等敏感信息。
- 不要替用户做出承诺或透露用户自己的隐私信息。
- 严格按 JSON schema 输出：一个对象，draft 字段是要发送的消息文本，不要输出任何其他文字或解释。
""".trim()
    }
}
