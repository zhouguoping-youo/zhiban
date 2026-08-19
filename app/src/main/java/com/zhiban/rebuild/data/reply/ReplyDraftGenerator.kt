package com.zhiban.rebuild.data.reply

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
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/** One recent message in the conversation, tagged by direction so the model learns the user's voice. */
data class ReplyThreadMessage(val outgoing: Boolean, val text: String)

data class ReplyDraftContext(
    val requestId: String,
    val contactName: String,
    val contactSummary: String?,
    val incomingMessage: String,
    val thread: List<ReplyThreadMessage>,
)

/**
 * One-shot reply drafting over the user's already-configured provider (same channel and key strategy as
 * "问问", no new provider path). The conversation leaves the device classified PERSONAL + AUTO_RETRIEVED,
 * so the fail-closed OutboundDataPolicy masks phone/email/id fragments; drafts are evidence the user
 * forwards by hand, never auto-sent. Output is constrained to a JSON object ({"drafts":[...]}) via the
 * provider's response_format schema so a small model can't drift into prose; parsing is tolerant of the
 * wrapper and markdown fences, capped and de-duplicated, with a bounded retry on any malformed batch.
 */
@Singleton
internal open class ReplyDraftGenerator @Inject constructor(private val provider: ProviderAdapter, private val profileStore: ProviderProfileStore) {

    open suspend fun generateDrafts(context: ReplyDraftContext): List<String> {
        val profile = profileStore.load() ?: return emptyList()
        val capability = runSuspendCatching { provider.probe(profile, context.requestId) }.getOrNull() ?: return emptyList()
        // Live probing showed the small flash model returns clean JSON only intermittently in free-form, and can
        // occasionally truncate even under a response_format schema. Keep the first parseable batch, retrying the
        // one-shot a bounded number of times so a single malformed reply doesn't cost the user a suggestion card.
        repeat(MAX_ATTEMPTS) { attempt ->
            val drafts = runSuspendCatching { draftOnce(context, profile, capability, attempt) }
                .onFailure { if (it is CancellationException) throw it }
                .getOrNull()
                .orEmpty()
            if (drafts.isNotEmpty()) return drafts
        }
        return emptyList()
    }

    private suspend fun draftOnce(context: ReplyDraftContext, profile: ProviderProfile, capability: CapabilitySnapshot, attempt: Int): List<String> {
        // requestId doubles as the outbound provenance sourceId, so keep it within the provenance charset.
        val requestId = "${context.requestId}-$attempt"
        val output = StringBuilder()
        var final = false
        provider.stream(
            ModelRequest(
                requestId = requestId,
                channel = OutboundChannel.LLM_INFERENCE,
                profile = profile,
                messages = listOf(
                    ModelMessage(
                        "system",
                        SYSTEM_PROMPT,
                        OutboundSensitivity.PUBLIC,
                        OutboundPurpose.SYSTEM_INSTRUCTION,
                        OutboundProvenance("system_policy", "reply-draft-v1"),
                    ),
                    ModelMessage(
                        "user",
                        buildPrompt(context),
                        OutboundSensitivity.PERSONAL,
                        OutboundPurpose.AUTO_RETRIEVED,
                        OutboundProvenance("conversation_thread", requestId),
                    ),
                ),
                capability = capability,
                maxTokens = minOf(MAX_OUTPUT_TOKENS, capability.maxOutputTokens),
                jsonSchema = DRAFTS_SCHEMA,
            ),
        ).collect { event ->
            when (event) {
                is ModelEvent.Delta -> if (output.length + event.text.length <= MAX_OUTPUT_CHARS) output.append(event.text)
                is ModelEvent.Final -> final = true
                is ModelEvent.ToolCall -> error("REPLY_DRAFT_TOOL_CALL_FORBIDDEN")
                is ModelEvent.Usage -> Unit
            }
        }
        if (!final) return emptyList()
        return parseDrafts(output.toString())
    }

    private fun buildPrompt(context: ReplyDraftContext): String {
        val lines = mutableListOf<String>()
        lines += "联系人：${context.contactName}"
        context.contactSummary?.takeIf { it.isNotBlank() }?.let { lines += "背景：$it" }
        if (context.thread.isNotEmpty()) {
            lines += "最近对话："
            context.thread.forEach { msg -> lines += "${if (msg.outgoing) "我" else "对方"}：${msg.text}" }
        }
        lines += "需要回复的最新消息：${context.incomingMessage}"
        lines += "请起草 2-3 条回复，填入 drafts 数组。"
        return lines.joinToString("\n")
    }

    private fun parseDrafts(raw: String): List<String> {
        val firstBracket = raw.indexOf('[')
        val lastBracket = raw.lastIndexOf(']')
        if (firstBracket < 0 || lastBracket < firstBracket) return emptyList()
        val array = runCatching { json.parseToJsonElement(raw.substring(firstBracket, lastBracket + 1)).jsonArray }
            .getOrNull() ?: return emptyList()
        return array.mapNotNull { element ->
            when (element) {
                is JsonPrimitive -> element.content
                is JsonObject -> DRAFT_KEYS.firstNotNullOfOrNull { key -> (element[key] as? JsonPrimitive)?.content }
                else -> null
            }
        }
            .map { it.trim() }
            .filter { it.length in 1..MAX_DRAFT_CHARS }
            .distinct()
            .take(MAX_DRAFTS)
    }

    private companion object {
        const val MAX_DRAFTS = 3
        const val MAX_DRAFT_CHARS = 120
        const val MAX_OUTPUT_CHARS = 4_096
        const val MAX_OUTPUT_TOKENS = 1_024
        const val MAX_ATTEMPTS = 2
        val DRAFT_KEYS = listOf("draft", "text", "reply")
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

        // Structured-output contract: forces the provider to emit a JSON object so a small model can't drift
        // into prose. parseDrafts tolerates the {"drafts":[...]} wrapper via bracket extraction.
        val DRAFTS_SCHEMA = """
{
  "name": "reply_drafts",
  "strict": true,
  "schema": {
    "type": "object",
    "properties": {"drafts": {"type": "array", "items": {"type": "string"}, "minItems": 2, "maxItems": 3}},
    "required": ["drafts"],
    "additionalProperties": false
  }
}
""".trim()

        val SYSTEM_PROMPT = """
你是知伴的回复起草助手，替用户为一条收到的消息起草可直接发送的中文回复。
要求：
- 起草 2-3 条草稿，每条不超过 120 字，口语化、自然。
- 语气贴合用户本人：参考对话里"我"发出的历史消息（称呼、标点、表情习惯）。
- 不要做没有依据的承诺（如"我一定到"），除非历史消息明确支持。
- 不要包含电话、邮箱、验证码、链接等敏感信息。
- 严格按 JSON schema 输出：一个对象，drafts 字段是 2-3 条字符串草稿，不要输出任何其他文字或解释。
""".trim()
    }
}
