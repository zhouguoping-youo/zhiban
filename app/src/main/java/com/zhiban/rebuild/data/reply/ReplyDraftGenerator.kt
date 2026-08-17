package com.zhiban.rebuild.data.reply

import com.zhiban.rebuild.runtime.provider.ModelEvent
import com.zhiban.rebuild.runtime.provider.ModelMessage
import com.zhiban.rebuild.runtime.provider.ModelRequest
import com.zhiban.rebuild.runtime.provider.OutboundChannel
import com.zhiban.rebuild.runtime.provider.OutboundProvenance
import com.zhiban.rebuild.runtime.provider.OutboundPurpose
import com.zhiban.rebuild.runtime.provider.OutboundSensitivity
import com.zhiban.rebuild.runtime.provider.ProviderAdapter
import com.zhiban.rebuild.runtime.provider.ProviderProfileStore
import javax.inject.Inject
import javax.inject.Singleton
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
 * forwards by hand, never auto-sent. Output is a JSON array of 2–3 short colloquial drafts, tolerant of
 * markdown fences, capped and de-duplicated.
 */
@Singleton
internal open class ReplyDraftGenerator @Inject constructor(private val provider: ProviderAdapter, private val profileStore: ProviderProfileStore) {

    open suspend fun generateDrafts(context: ReplyDraftContext): List<String> {
        val profile = profileStore.load() ?: return emptyList()
        val capability = runCatching { provider.probe(profile, context.requestId) }.getOrNull() ?: return emptyList()
        val output = StringBuilder()
        var final = false
        provider.stream(
            ModelRequest(
                requestId = context.requestId,
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
                        OutboundProvenance("conversation_thread", context.requestId),
                    ),
                ),
                capability = capability,
                maxTokens = minOf(MAX_OUTPUT_TOKENS, capability.maxOutputTokens),
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
        lines += "请起草 2-3 条回复。"
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
        const val MAX_OUTPUT_TOKENS = 512
        val DRAFT_KEYS = listOf("draft", "text", "reply")
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

        val SYSTEM_PROMPT = """
你是知伴的回复起草助手，替用户为一条收到的消息起草可直接发送的中文回复。
要求：
- 输出 2-3 条草稿，每条不超过 120 字，口语化、自然。
- 语气贴合用户本人：参考对话里"我"发出的历史消息（称呼、标点、表情习惯）。
- 不要做没有依据的承诺（如"我一定到"），除非历史消息明确支持。
- 不要包含电话、邮箱、验证码、链接等敏感信息。
- 只输出一个 JSON 数组（元素是字符串），不要输出任何其他文字、解释或 Markdown 代码块。
""".trim()
    }
}
