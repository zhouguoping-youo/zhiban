package com.zhiban.rebuild.data.reply

import com.zhiban.rebuild.data.notification.SensitiveMessageFilter

/**
 * Rule-first gate deciding whether an incoming message is worth an AI-drafted reply. Pure and free
 * (no model call) so the coordinator only pays for generation on a clear signal — 宁缺勿滥. A message
 * is worthy only when it directly asks the user something or assigns them a task; casual chat and
 * plain statements stay below the threshold. Marketing/OTP, an already-answered thread, and an
 * unresolvable forward target are hard vetoes, not low scores.
 */
internal object ReplyWorthinessAnalyzer {
    data class Verdict(val worthy: Boolean, val score: Double, val reasonCode: String)

    fun evaluate(text: String?, hasAttribution: Boolean, hasLaterOutgoing: Boolean): Verdict {
        val normalized = text.orEmpty().trim()
        if (normalized.isEmpty()) return Verdict(false, 0.0, "EMPTY")
        if (!hasAttribution) return Verdict(false, 0.0, "NO_FORWARD_TARGET")
        if (hasLaterOutgoing) return Verdict(false, 0.0, "ALREADY_REPLIED")
        if (SensitiveMessageFilter.shouldDrop(normalized) || MARKETING_WORDS.any(normalized.lowercase()::contains)) {
            return Verdict(false, 0.0, "MARKETING_OR_SENSITIVE")
        }
        val score = when {
            isQuestion(normalized) -> SCORE_QUESTION
            isTaskDirected(normalized) -> SCORE_TASK
            isLowValue(normalized) -> SCORE_LOW_VALUE
            else -> SCORE_PLAIN
        }
        return Verdict(score >= WORTHY_THRESHOLD, score, if (score >= WORTHY_THRESHOLD) "REPLY_WORTHY" else "LOW_SIGNAL")
    }

    private fun isQuestion(text: String): Boolean {
        if (text.contains('？') || text.contains('?')) return true
        val stripped = text.trimEnd('。', '！', '!', '~', '～', ' ', '，', ',')
        if (QUESTION_SUFFIXES.any(stripped::endsWith)) return true
        return QUESTION_WORDS.any(text::contains)
    }

    private fun isTaskDirected(text: String): Boolean = TASK_WORDS.any(text::contains)

    private fun isLowValue(text: String): Boolean {
        val compact = text.replace(Regex("[\\s，。、,.!！~～]"), "")
        if (compact.isEmpty()) return true // emoji / punctuation only
        return compact.length <= 3 && LOW_VALUE_ACKS.any { compact.equals(it, ignoreCase = true) }
    }

    private const val SCORE_QUESTION = 0.9
    private const val SCORE_TASK = 0.85
    private const val SCORE_PLAIN = 0.3
    private const val SCORE_LOW_VALUE = 0.1
    private const val WORTHY_THRESHOLD = 0.6

    private val QUESTION_SUFFIXES = listOf("吗", "呢", "吧", "嘛", "么", "没有", "行不行", "好不好", "可以吗", "在吗", "在不在")
    private val QUESTION_WORDS = listOf("什么", "什么时候", "几点", "多少", "哪", "哪里", "哪儿", "怎么", "怎么样", "为什么", "能不能", "能否", "方便吗", "帮我", "在吗", "在不在")
    private val TASK_WORDS = listOf("发我", "发给我", "给我", "麻烦", "尽快", "截止", "别忘了", "记得", "务必", "催一下", "帮忙", "拜托")
    private val LOW_VALUE_ACKS = listOf("哈哈", "嗯", "嗯嗯", "哦", "哦哦", "好", "好的", "ok", "行", "可以", "谢谢", "收到", "知道", "对", "是的", "嘿嘿", "呵呵")
    private val MARKETING_WORDS = listOf("退订", "回复td", "回t退订", "广告", "促销", "优惠券", "拼团", "秒杀", "点击领取", "免费领取", "关注公众号", "限时抢购")
}
