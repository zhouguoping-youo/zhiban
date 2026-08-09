package com.zhiban.rebuild.data.agent

import com.zhiban.rebuild.data.notification.NotificationInsightAnalyzer

internal object ContactFactDisplayNormalizer {
    private val prefix = Regex(
        """^(?<wrapper>\s*(?:【[^】]{0,24}】|\[[^]]{0,24}]|[（(][^）)]{0,24}[)）])?\s*)""" +
            """(?<actor>我方|你方|对方|我|你|他|她|它|客服|系统|知伴|服务号|公众号|消息中心)?\s*""" +
            """(?<counterpart>[（(][^）)]{0,24}[)）])?\s*""" +
            """(?<context>[·•:：,，、-]?\s*(?:消息|通知|聊天|最近消息|系统消息|对话摘要|消息原文)?\s*)""" +
            """(?:(?<channel>[\p{L}\p{N}_·-]{1,16})(?=说到|说|提到|提及|发来|发出|告诉|反馈|回复|转告|通知|确认))?\s*""" +
            """(?<verb>说到|说|提到|提及|发来|发出|告诉|反馈|回复|转告|通知|确认)?\s*[:：]?\s*""",
    )
    private val recentMention = Regex("""\s*[·•]?\s*最近提到[:：]?\s*""")
    private val leadingRequest = Regex("""^\s*(?:请|帮我|麻烦|先|先给我)\s*""")
    private val whitespace = Regex("""\s{2,}""")

    fun normalize(sourceType: String, factType: String, value: String): String {
        val sourceAware = sourceType in CONTACT_FACT_SOURCE_TYPES || factType in CONTACT_FACT_TYPES
        var body = value.trim()
        repeat(MAX_PREFIX_PASSES) {
            val match = prefix.find(body)?.takeIf { it.range.first == 0 && it.value.isNotBlank() } ?: return@repeat
            val meaningfulPrefix = listOf("wrapper", "actor", "counterpart", "context", "channel", "verb")
                .any { name -> match.groups[name]?.value?.isNotBlank() == true }
            if (!meaningfulPrefix) return@repeat
            body = body.removeRange(match.range).trimStart()
        }
        body = recentMention.replaceFirst(body, "")
            .replace(leadingRequest, "")
            .trim('“', '”', '"', '\'', '，', '。', '：', ':', ' ', '\t', '\n')
        if (body.isBlank()) return EMPTY_LABEL

        val scheduleTitle = NotificationInsightAnalyzer.sanitizeScheduleTitle(body, null)
        val normalized = if (sourceAware && scheduleTitle !in setOf("", "待确认安排", "沟通内容")) {
            scheduleTitle
        } else {
            NotificationInsightAnalyzer.normalizeConversationSnippet(body)
        }
        return whitespace.replace(normalized, " ")
            .trim()
            .ifBlank { EMPTY_LABEL }
            .let { if (it.length <= MAX_LENGTH) it else "${it.take(MAX_LENGTH - 3)}…" }
    }

    private const val MAX_PREFIX_PASSES = 3
    private const val MAX_LENGTH = 120
    private const val EMPTY_LABEL = "已确认的沟通内容"
    private val CONTACT_FACT_SOURCE_TYPES = setOf(
        "USER_CONFIRMED",
        "USER_CONFIRMED_NOTIFICATION",
        "AGENT_DOMAIN_WRITE",
        "USER_CONFIRMED_MEMORY",
        "OBSERVED_NOTIFICATION",
    )
    private val CONTACT_FACT_TYPES = setOf(
        "CONTACT_MEMORY",
        "IMPORTANT_DATE",
        "COMMUNICATION_PREFERENCE",
        "CURRENT_MATTER",
        "INTERACTION_SUMMARY",
    )
}
