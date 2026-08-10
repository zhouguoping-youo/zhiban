package com.zhiban.rebuild.data.notification

import android.app.Notification
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import java.security.MessageDigest
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

data class SocialPlatform(val code: String, val label: String, val packages: Set<String>)

object SocialAppCatalog {
    private val platforms = listOf(
        SocialPlatform("WECHAT", "微信", setOf("com.tencent.mm")),
        SocialPlatform("QQ", "QQ", setOf("com.tencent.mobileqq", "com.tencent.mobileqqi")),
        SocialPlatform("TIM", "TIM", setOf("com.tencent.tim")),
        SocialPlatform("FEISHU", "飞书", setOf("com.ss.android.lark")),
        SocialPlatform("LARK", "Lark", setOf("com.larksuite.suite")),
        SocialPlatform("WEWORK", "企业微信", setOf("com.tencent.wework")),
        SocialPlatform("DINGTALK", "钉钉", setOf("com.alibaba.android.rimet")),
        SocialPlatform(
            "SMS",
            "短信",
            setOf(
                "com.android.mms",
                "com.android.messaging",
                "com.google.android.apps.messaging",
                "com.samsung.android.messaging",
                "com.samsung.android.messagingui",
                "com.samsung.android.app.messaging",
                "com.coloros.mms",
                "com.oplus.mms",
                "com.vivo.message",
                "com.huawei.android.messaging",
                "com.huawei.mms",
            ),
        ),
    )

    fun platformForPackage(packageName: String): SocialPlatform? {
        val normalized = packageName.lowercase()
        platforms.firstOrNull { normalized in it.packages }?.let { return it }
        return when {
            normalized.contains("tencent.mm") || normalized.contains("wechat") || normalized.contains("weixin") ->
                platforms.first { it.code == "WECHAT" }

            normalized.contains("mobileqq") -> platforms.first { it.code == "QQ" }

            normalized.contains("larksuite") -> platforms.first { it.code == "LARK" }

            normalized.contains("ss.android.lark") -> platforms.first { it.code == "FEISHU" }

            normalized.contains("wework") -> platforms.first { it.code == "WEWORK" }

            normalized.contains("dingtalk") || normalized.contains("alibaba.android.rimet") ->
                platforms.first { it.code == "DINGTALK" }

            isSmsPackage(normalized) -> platforms.first { it.code == "SMS" }

            else -> null
        }
    }

    fun isSupported(packageName: String): Boolean = platformForPackage(packageName) != null

    private fun isSmsPackage(value: String): Boolean = value.contains(".messaging") || value.contains(".mms") ||
        value.endsWith(".sms") || value.contains(".messages")
}

data class NotificationMessageSnapshot(val sender: String?, val text: String?, val timestamp: Long?)

data class SocialNotificationSnapshot(
    val packageName: String,
    val notificationKey: String,
    val postTimeEpochMs: Long,
    val appLabel: String,
    val title: String?,
    val text: String?,
    val bigText: String?,
    val conversationTitle: String?,
    val selfDisplayName: String?,
    val messages: List<NotificationMessageSnapshot>,
    val category: String?,
    val isOngoing: Boolean,
    val userHandle: String?,
)

data class ScheduleInsight(
    val title: String,
    val startAtEpochMs: Long,
    val durationMinutes: Int = 60,
    val reminderMinutesBefore: Int = 10,
    val confidence: Double,
) {
    fun toJson() = buildJsonObject {
        put("title", title)
        put("startAtEpochMs", startAtEpochMs)
        put("durationMinutes", durationMinutes)
        put("reminderMinutesBefore", reminderMinutesBefore)
        put("confidence", confidence)
    }

    companion object {
        fun from(candidate: NotificationCandidateEntity): ScheduleInsight? {
            val raw = candidate.insightJson ?: return null
            return runCatching {
                val value = Json.parseToJsonElement(raw).jsonObject["schedule"]?.jsonObject
                    ?: return@runCatching null
                ScheduleInsight(
                    title = value["title"]?.jsonPrimitive?.content ?: return@runCatching null,
                    startAtEpochMs = value["startAtEpochMs"]?.jsonPrimitive?.longOrNull
                        ?: return@runCatching null,
                    durationMinutes = value["durationMinutes"]?.jsonPrimitive?.intOrNull ?: 60,
                    reminderMinutesBefore = value["reminderMinutesBefore"]?.jsonPrimitive?.intOrNull ?: 10,
                    confidence = value["confidence"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                )
            }.getOrNull()
        }
    }
}

data class NotificationInsights(val schedule: ScheduleInsight? = null) {
    fun toJsonOrNull(): String? = schedule?.let {
        buildJsonObject {
            put("schemaVersion", 1)
            put("schedule", it.toJson())
        }.toString()
    }
}

object NotificationInsightAnalyzer {
    private val actionWords = listOf(
        "开会", "会议", "见面", "碰面", "拜访", "面试", "约", "安排",
        "日程", "提醒", "参加", "集合", "出发", "回访", "电话沟通",
    )
    private val negativeWords = listOf("取消", "不用", "不去", "改天", "暂不", "无需")

    fun analyze(
        text: String,
        senderName: String?,
        conversationTitle: String?,
        postedAtEpochMs: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): NotificationInsights {
        val normalized = text.replace(Regex("\\s+"), " ").trim()
        if (normalized.isBlank() || actionWords.none(normalized::contains) || negativeWords.any(normalized::contains)) {
            return NotificationInsights()
        }
        val date = resolveDate(normalized, postedAtEpochMs, zoneId) ?: return NotificationInsights()
        val time = resolveTime(normalized) ?: return NotificationInsights()
        val start = date.atTime(time).atZone(zoneId).toInstant().toEpochMilli()
        if (start < postedAtEpochMs - 5 * 60_000L) return NotificationInsights()
        val source = senderName ?: conversationTitle
        val title = cleanScheduleTitle(normalized, source).ifBlank { "待确认安排" }
        return NotificationInsights(
            ScheduleInsight(
                title = title,
                startAtEpochMs = start,
                confidence = scheduleConfidence(normalized),
            ),
        )
    }

    private fun scheduleConfidence(text: String): Double = when {
        ABSOLUTE_DATE.containsMatchIn(text) -> 0.99
        listOf("今天", "明天", "后天").any(text::contains) -> 0.99
        "下周" in text || "下星期" in text -> 0.96
        WEEKDAY.containsMatchIn(text) -> 0.94
        else -> 0.92
    }

    internal fun sanitizeScheduleTitle(rawText: String, source: String?): String = cleanScheduleTitle(rawText, source)

    internal fun normalizeConversationSnippet(value: String): String = removeEmbeddedTemporalLeading(value)
        .replace(LEADING_TEMPORAL_PREFIX, "")
        .replace(Regex("""^\s*[，,。；;:：\s]+"""), "")
        .replace(Regex("""\s{2,}"""), " ")
        .trim()
        .trim('“', '”', '"', '\'')
        .let { if (it.isBlank()) "沟通内容" else it }

    private fun cleanScheduleTitle(rawText: String, source: String?): String {
        val normalizedSource = source?.trim().orEmpty()
        var title = normalizeConversationSnippet(rawText).let { if (it == "沟通内容") rawText.trim() else it }
        val trimmedSource = source?.trim().orEmpty()
        title = stripLeadingContactPlatformPrefix(title)
        title = title.replace(Regex("""^(?:${Regex.escape(trimmedSource)}\s*[，,、]\s*)""", RegexOption.IGNORE_CASE), "")
        if (trimmedSource.isNotBlank()) {
            title = title.replace(
                Regex(
                    """^\s*(?:${Regex.escape(
                        trimmedSource,
                    )}\s*[：:]\s*|${Regex.escape(
                        trimmedSource,
                    )}\s*[，,、]\s*|${Regex.escape(trimmedSource)}\s*[,，.:：]?\s*)""",
                ),
                "",
            )
        }
        title = stripLeadingUnknownSpeakerPrefix(title)
        title = stripLeadingAttributionPrefix(title)
        title = stripLeadingContactPlatformPrefix(title)
        title = normalizeInlineAttribution(title)
        title = stripTemporalLeadingConnective(title)
        title = stripLeadingActionObjectPrefix(title)
        title = stripLeadingTemporalWithObjectPrefix(title)
        title = normalizeLeadingTimelinePrefix(title)
        title = title.replace(
            TIMESTAMP_START_PREFIX,
            "",
        )
        title = title.replace(Regex("""^\s*(?:请|麻烦|先|先给我|先发|帮我)\s*"""), "")
        title = removeEmbeddedTemporalLeading(title)
        title = title.replace(LEADING_TEMPORAL_PREFIX, "")
        title = title.replace(Regex("""^(?:我|你|对方|他|她|它|我们|你们)\s*(?:先|刚?)?\s*[：,:：]?\s*"""), "")
        title = title.replace("明天下下午", "明天下午")
        title = title.replace(Regex("""明天\s*下(?=下午|中午|早上|晚上|凌晨|傍晚|早间|晚间)"""), "明天")
        title = title.replace(Regex("""^\s*[，,。:：\s]+"""), "")
        title = title.replace(
            Regex("""^\s*(?:[我你对方]\s*)?(?:\(.*?\)|（.*?）)?\s*(?:发来|说|提到|提及|发自|回复|回执|通知)[:：]?\s*"""),
            "",
        )
        title = title.replace(Regex("""^(?:关于|关于你|关于我)\s*"""), "")
        title = title.replace(Regex("""^\s*和(?:我|你|他|她|它|对方)?\s*"""), "")
        if (title.length > 26) title = title.take(26)
        title = title.trim('，', '、', ',', '。', ' ', '\t', '\n')
        title = title.replace(Regex("""\s+"""), " ")

        if (title.isBlank()) {
            return if (normalizedSource.isNotBlank()) {
                "${normalizedSource}的安排"
            } else if (rawText.trim().isNotBlank()) {
                rawText.trim().take(26)
            } else {
                "待确认安排"
            }
        }
        return title
    }

    private fun stripLeadingActionObjectPrefix(value: String): String {
        var title = value
        title = title.replace(Regex("""^\s*(?:和(?:我|你|他|她|它|对方))\s*"""), "")
        title = title.replace(Regex("""^\s*(?:给(?:我|你|他|她|它|对方))\s*"""), "")
        title = title.replace(Regex("""^\s*(?:先|请|帮我|麻烦)\s*"""), "")
        return title
    }

    private fun stripLeadingTemporalWithObjectPrefix(value: String): String {
        var title = value
        val next = title.replace(
            Regex(
                """^\s*(?:(?:今天|明天|后天|前天|本周|下周|下星期|周[一二三四五六日天]|星期[一二三四五六日天])\s*(?:[上中下]?\s*)?(?:[零一二三四五六七八九十]{0,3}|[0-2]?\d)\s*(?:[:：]\s*\d{1,2}|点(?:\s*(?:半|[0-5]?\d|[零一二三四五六七八九十]{1,3})?)\s*)\s*(?:[，,、]?\s*和我?)?\s*(?=[\u4e00-\u9fffA-Za-z0-9]))""",
            ),
            "",
        )
        if (next != title) return next
        return value.replace(
            Regex(
                """^\s*(?:[上中下]?\s*)?(?:[零一二三四五六七八九十]{0,3}|[0-2]?\d)\s*(?:[:：]\s*\d{1,2}|点(?:\s*(?:半|[0-5]?\d|[零一二三四五六七八九十]{1,3})?)\s*)\s*点?\s*(?:[，,、]?\s*和我?)?\s*(?=[\u4e00-\u9fffA-Za-z0-9])""",
            ),
            "",
        )
    }

    private fun stripLeadingContactPlatformPrefix(value: String): String {
        var title = value.trim()
        var changed: Boolean
        do {
            changed = false
            val next = title
                .replace(
                    Regex("""^\s*(?:\[[^\]]{1,24}\]|【[^】]{1,24}】|\([^)]{1,24}\)|（[^）]{1,24}）)\s*[·•]?\s*"""),
                    "",
                )
                .replace(
                    Regex(
                        """^\s*(?:我方|我|你|对方|他|她|它|客服|系统|知伴)\s*[·•]?\s*(?:说|提到|提及|发来|告诉|反馈|回复|转告|转达|通知|确认)?\s*[:：]?\s*""",
                    ),
                    "",
                )
                .replace(
                    Regex(
                        """^\s*(?:对方|他|她|它)?\s*(?:刚刚|刚|先|刚发)?\s*(?:说|提到|提及|发来|告诉|反馈|回复|转告|转达|通知)[:：]?\s*""",
                        RegexOption.IGNORE_CASE,
                    ),
                    "",
                )
            if (next != title) {
                changed = true
                title = next
            }
        } while (changed && title.isNotBlank())
        return title
    }

    private fun normalizeLeadingTimelinePrefix(value: String): String {
        var title = value
        title = title.replace("明天下下午", "明天下午")
        title = title.replace(Regex("""^\s*明天\s*下(?=下午|中午|早上|晚上|凌晨|傍晚|早间|晚间)"""), "明天")
        title = title.replace(
            Regex(
                """^\s*(?:今天|明天|后天|前天|本周|下周|下星期|周[一二三四五六日天]|星期[一二三四五六日天])\s*(?:[上中下]?\s*)?(?:[零一二三四五六七八九十]{0,3}|[0-2]?\d)\s*(?:[:：]\s*\d{1,2}|点(?:\s*(?:半|[0-5]?\d|[零一二三四五六七八九十]{1,3})?)\s*)\s*""",
            ),
            "",
        )
        title = title.replace(Regex("""^\s*(?:[0-1]?\d|[二三四五六七八九十])\s*点(?:\s*半)?\s*"""), "")
        title = title.trim()
        return title
    }

    private fun stripLeadingUnknownSpeakerPrefix(value: String): String {
        if (value.isBlank()) return value
        val match = Regex(
            """^\s*([\w\d_·\-\u4e00-\u9fff]{1,24})\s*[，,、:：]\s*(.+)$""",
            RegexOption.IGNORE_CASE,
        ).matchEntire(value)
            ?: return value
        val speaker = match.groupValues[1].trim()
        val body = match.groupValues[2].trim()
        return if (looksLikePersonalName(speaker) && looksLikeScheduleBody(body)) body else value
    }

    private fun looksLikePersonalName(value: String): Boolean {
        if (value.isBlank() || value.length > 24) return false
        if (value.any { it.isWhitespace() }) return false
        if (value.any { it == '：' || it == '，' || it == ',' }) return false
        if (looksLikeScheduleBody(value)) return false
        if (looksLikeTemporalWords(value)) return false
        return true
    }

    private fun looksLikeTemporalWords(value: String): Boolean {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return false
        return Regex("""^(?:今|明|后|周|星期|下周|下星期|上午|下午|晚上|早上|凌晨|傍晚)""").containsMatchIn(trimmed)
    }

    private fun stripLeadingAttributionPrefix(rawText: String): String {
        var title = rawText
        if (title.isBlank()) return title
        title = title.replace(Regex("""^\s*(?:我|你|他|她|它|对方|系统|知伴)\s*[，,、]\s*"""), "")
        title = title.replace(Regex("""^\s*[（(][^）)]{0,24}[)）]\s*"""), "")
        LEADING_NAME_PREFIX.find(title)?.let { match ->
            val tail = match.groupValues[2].trim()
            if (tail.isNotBlank() && looksLikeScheduleBody(tail)) {
                title = tail
            }
        }
        if (looksLikeScheduleBody(title)) {
            title = removeEmbeddedTemporalLeading(title)
            title = title.replace(LEADING_TEMPORAL_PREFIX, "")
        }
        return title
    }

    private fun normalizeInlineAttribution(value: String): String {
        var title = value
        title = title.replace(
            Regex(
                """^\s*(?:\(|（)?[\w\d_·\-\u4e00-\u9fff]{1,24}(?:\)|）)?\s*(?:说|提到|提及|发来|说起|提醒)[:：]\s*""",
                RegexOption.IGNORE_CASE,
            ),
            "",
        )
        title = title.replace(
            Regex(
                """^\s*(?:\[[^\]]{1,32}\]|【[^】]{1,32}】|\([^\)]{1,32}\)|（[^）]{1,32}）)\s*(?:说|提到|提及|发来|提醒)[:：]\s*""",
                RegexOption.IGNORE_CASE,
            ),
            "",
        )
        return title
    }

    private fun stripTemporalLeadingConnective(rawText: String): String {
        var title = rawText
        title = title.replace(Regex("""^\s*(?:我|你|他|她|它|对方)\s*(?:对|对着|向)\s*(?:你|他|她|对方)?\s*(?:说|提到|提及)\s*"""), "")
        title = title.replace(Regex("""^\s*你们\s*(?:明天|今天|后天|本周|下周)\s*"""), "")
        title = title.replace(
            Regex(
                """^\s*(?:今天|明天|后天|前天|本周|下周|下星期|周[一二三四五六日天]|星期[一二三四五六日天])\s*[上下]?\s*(?:早上|中午|下午|早间|晚间|傍晚|早间|晚间|晚上|凌晨)""",
            ),
            "",
        )
        return title
    }

    private fun hasTemporalOrScheduleLead(value: String): Boolean =
        Regex("""^\s*(?:(?:今天|明天|后天|前天|本周|下周|下星期|周[一二三四五六日天]|星期[一二三四五六日天])\s*)""").containsMatchIn(value) ||
            Regex(
                """^\s*(?:[上中下]?\s*)?(?:[0-2]?\d|[零一二三四五六七八九十]{1,3})(?:[:：]\s*[0-5]?\d|点(?:\s*(?:半|[0-5]?\d|[零一二三四五六七八九十]{1,3})?)?)\b""",
            ).containsMatchIn(value) ||
            Regex(
                """^\s*(?:[上下]?(?:上午|中午|下午|晚上|早上|凌晨|傍晚)\s*)?(?:[0-2]?\d|[零一二三四五六七八九十]{1,3})(?:[:：]\s*[0-5]?\d|点(?:\s*(?:半|[0-5]?\d|[零一二三四五六七八九十]{1,3})?)?)\b""",
            ).containsMatchIn(value)

    private fun hasScheduleActionLead(value: String): Boolean = scheduleLeadActionWords.any {
        Regex("""^\s*$it\b""").containsMatchIn(value)
    }

    private fun looksLikeScheduleBody(value: String): Boolean {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return false
        return hasTemporalOrScheduleLead(trimmed) || hasScheduleActionLead(trimmed) || trimmed.contains("明天")
    }

    private val scheduleLeadActionWords = listOf(
        "开会",
        "会议",
        "见面",
        "碰面",
        "拜访",
        "面试",
        "约",
        "安排",
        "日程",
        "提醒",
        "参加",
        "集合",
        "出发",
        "回访",
        "通话",
        "电话",
        "回复",
        "处理",
        "讨论",
    )

    private val LEADING_NAME_PREFIX = Regex(
        """^\s*([A-Za-z0-9_\-·\u4e00-\u9fff]{1,30})\s*[，,、:：]\s*(.+)$""",
    )

    private fun resolveDate(text: String, nowEpochMs: Long, zoneId: ZoneId): LocalDate? {
        val today = Instant.ofEpochMilli(nowEpochMs).atZone(zoneId).toLocalDate()
        if ("今天" in text) return today
        if ("后天" in text) return today.plusDays(2)
        if ("明天" in text) return today.plusDays(1)
        ABSOLUTE_DATE.find(text)?.let { match ->
            val month = match.groupValues[1].toIntOrNull() ?: return@let
            val day = match.groupValues[2].toIntOrNull() ?: return@let
            return runCatching {
                var value = LocalDate.of(today.year, month, day)
                if (value.isBefore(today.minusDays(1))) value = value.plusYears(1)
                value
            }.getOrNull()
        }
        WEEKDAY.find(text)?.let { match ->
            val target = chineseWeekday(match.groupValues[1]) ?: return@let
            var value = today.with(TemporalAdjusters.nextOrSame(target))
            if (value == today && ("下周" in text || "下星期" in text)) value = value.plusWeeks(1)
            return value
        }
        return null
    }

    private fun resolveTime(text: String): LocalTime? {
        val match = CLOCK.find(text) ?: return null
        val upDownPrefix = match.groupValues[1]
        val marker = match.groupValues[2]
        val rawHour = match.groupValues[3].toIntOrNull()
            ?: chineseNumber(match.groupValues[3])
            ?: return null
        val minute = when {
            match.groupValues[4].isNotBlank() -> match.groupValues[4].toIntOrNull() ?: return null
            match.groupValues[5] == "半" -> 30
            else -> 0
        }
        if (rawHour !in 0..23 || minute !in 0..59) return null
        val hour = when {
            upDownPrefix == "下" && rawHour in 1..11 -> rawHour + 12
            marker in setOf("下午", "晚上", "中午") && rawHour in 1..11 -> rawHour + 12
            marker == "凌晨" && rawHour == 12 -> 0
            else -> rawHour
        }
        return runCatching { LocalTime.of(hour, minute) }.getOrNull()
    }

    private fun chineseNumber(value: String): Int? = when (value) {
        "零" -> 0
        "一" -> 1
        "二", "两" -> 2
        "三" -> 3
        "四" -> 4
        "五" -> 5
        "六" -> 6
        "七" -> 7
        "八" -> 8
        "九" -> 9
        "十" -> 10
        "十一" -> 11
        "十二" -> 12
        else -> null
    }

    private fun chineseWeekday(value: String): DayOfWeek? = when (value) {
        "一" -> DayOfWeek.MONDAY
        "二" -> DayOfWeek.TUESDAY
        "三" -> DayOfWeek.WEDNESDAY
        "四" -> DayOfWeek.THURSDAY
        "五" -> DayOfWeek.FRIDAY
        "六" -> DayOfWeek.SATURDAY
        "日", "天" -> DayOfWeek.SUNDAY
        else -> null
    }

    private val ABSOLUTE_DATE = Regex("""(\d{1,2})\s*月\s*(\d{1,2})\s*[日号]""")
    private val WEEKDAY = Regex("""(?:本周|这周|下周|星期|周|下星期)\s*([一二三四五六日天])""")
    private val CLOCK = Regex(
        """([上中下])?\s*(凌晨|早上|上午|中午|下午|晚上|傍晚)?\s*([0-2]?\d|零|一|二|两|三|四|五|六|七|八|九|十|十一|十二)\s*(?:[:：\s]*(\d{1,2})?\s*分?|点\s*(半|[0-5]?\d|[零一二三四五六七八九十]{1,3})?)""",
    )
    private val LEADING_TEMPORAL_PREFIX = Regex(
        """^\s*(?:(?:今天|明天|后天|前天|本周|下周|下星期|周[一二三四五六日天]|星期[一二三四五六日天])\s*)?(?:(?:[上下]?(?:上午|中午|下午|晚上|早上|凌晨|傍晚)\s*)?)(?:[上中下]\s*)?(?:[0-2]?\d|[零一二三四五六七八九十]{1,3})\s*(?:[:：]\s*\d{1,2}|点(?:\s*(?:半|[0-5]?\d|[零一二三四五六七八九十]{1,3})?)\s*)(?:\s*分?)\s*[,，:：.]?\s*""",
    )
    private val TIMESTAMP_START_PREFIX = Regex(
        """^\s*(?:(?:今天|明天|后天|前天|本周|下周|下星期|周[一二三四五六日天]|星期[一二三四五六日天])\s*)?(?:(?:[上下]?(?:上午|中午|下午|晚上|早上|凌晨|傍晚)\s*)?)(?:[上中下]\s*)?(?:[0-2]?\d|[零一二三四五六七八九十]{1,3})(?:[:：]\s*\d{1,2}|点(?:\s*(?:半|[0-5]?\d|[零一二三四五六七八九十]{1,3})?)?)?\s*""",
    )

    private fun removeEmbeddedTemporalLeading(value: String): String {
        var cleaned = value
        var before = ""
        while (before != cleaned) {
            before = cleaned
            cleaned = cleaned.replace(TIMESTAMP_START_PREFIX, "").trimStart()
        }
        return cleaned
    }
}

object SocialNotificationParser {
    fun parse(snapshot: SocialNotificationSnapshot): NotificationCandidateEntity? {
        val platform = SocialAppCatalog.platformForPackage(snapshot.packageName) ?: return null
        if (snapshot.isOngoing || snapshot.category == Notification.CATEGORY_SERVICE) return null
        if (snapshot.packageName == "android" && snapshot.category != Notification.CATEGORY_MESSAGE) return null

        val title = cleanTitle(snapshot.conversationTitle ?: snapshot.title)
        val latest = snapshot.messages.lastOrNull { !it.text.isNullOrBlank() }
        val rawText = latest?.text ?: snapshot.bigText ?: snapshot.text
        val cleanText = cleanText(rawText) ?: return null
        if (SensitiveMessageFilter.shouldDrop(cleanText)) return null

        val prefixed = GROUP_MESSAGE.find(cleanText)
        val prefixedSender = prefixed?.groupValues?.get(1)?.trim()?.takeIf(::isUsefulSender)
        val latestSender = latest?.sender?.trim()?.takeIf(::isUsefulSender)
        val isOutgoing = !latestSender.isNullOrBlank() &&
            latestSender.equals(snapshot.selfDisplayName?.trim(), ignoreCase = true)
        val isGroup = !prefixedSender.isNullOrBlank() && !title.equals(prefixedSender, ignoreCase = true)
        val sender = when {
            isOutgoing -> title
            isGroup -> prefixedSender
            !latestSender.isNullOrBlank() && !latestSender.equals(title, ignoreCase = true) -> latestSender
            platform.code == "SMS" || isUsefulSender(title) -> title
            else -> null
        }?.take(80)
        if (sender == null && title == null) return null
        if (!isGroup && isServiceSender(sender ?: title.orEmpty())) return null

        val body = if (isGroup) prefixed?.groupValues?.get(2)?.trim() else cleanText
        if (platform.code == "SMS" && isNonPersonalSms(sender, body)) return null
        val conversation = title ?: sender
        val sourceKey = sha256(
            listOf(
                snapshot.packageName,
                snapshot.userHandle.orEmpty(),
                conversation.orEmpty(),
                sender.orEmpty(),
                body.orEmpty(),
                snapshot.postTimeEpochMs.toString(),
            ).joinToString("|"),
        )
        val insights = NotificationInsightAnalyzer.analyze(
            text = body.orEmpty(),
            senderName = sender,
            conversationTitle = conversation,
            postedAtEpochMs = snapshot.postTimeEpochMs,
        )
        return NotificationCandidateEntity(
            candidateId = "notification-${sourceKey.take(32)}",
            sourceKey = sourceKey,
            packageName = snapshot.packageName.take(200),
            appLabel = platform.label,
            title = conversation?.take(200),
            body = body?.take(1_000),
            postedAtEpochMs = snapshot.postTimeEpochMs,
            sourceType = "NOTIFICATION",
            platform = platform.code,
            conversationTitle = conversation?.take(200),
            senderName = sender,
            direction = if (isOutgoing) "OUTGOING" else "INCOMING",
            isGroupChat = isGroup,
            messageKind = if (insights.schedule != null) "SCHEDULE_CANDIDATE" else "MESSAGE",
            insightJson = insights.toJsonOrNull(),
        )
    }

    @Suppress("DEPRECATION")
    fun messagingStyleMessages(extras: Bundle?): List<NotificationMessageSnapshot> {
        if (extras == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return emptyList()
        val bundles = runCatching {
            extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        }.getOrNull() ?: return emptyList()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                Notification.MessagingStyle.Message.getMessagesFromBundleArray(bundles)
                    .map {
                        NotificationMessageSnapshot(
                            sender = it.senderPerson?.name?.toString(),
                            text = it.text?.toString(),
                            timestamp = it.timestamp,
                        )
                    }
            }.getOrDefault(emptyList())
        } else {
            bundles.mapNotNull(::legacyMessage)
        }
    }

    private fun legacyMessage(value: Parcelable): NotificationMessageSnapshot? {
        val bundle = value as? Bundle ?: return null
        return NotificationMessageSnapshot(
            sender = bundle.getCharSequence("sender")?.toString(),
            text = bundle.getCharSequence("text")?.toString(),
            timestamp = bundle.getLong("time").takeIf { it > 0 },
        )
    }

    private fun cleanTitle(value: String?): String? = value
        ?.replace(Regex("""^\[\d+条?]\s*"""), "")
        ?.replace(Regex("""\s*\(\d+\)\s*$"""), "")
        ?.replace(Regex("""\s*（\d+条?新消息）\s*$"""), "")
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        ?.takeIf(String::isNotBlank)

    private fun cleanText(value: String?): String? = value
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        ?.takeIf(String::isNotBlank)

    private fun isUsefulSender(value: String?): Boolean {
        val clean = value?.trim().orEmpty()
        return clean.length in 1..80 && clean.none { it == '\n' }
    }

    private fun isServiceSender(value: String): Boolean {
        val normalized = value.replace(" ", "")
        return listOf(
            "服务通知",
            "系统通知",
            "微信支付",
            "QQ安全中心",
            "团队助手",
            "订阅号消息",
            "应用通知",
            "安全中心",
        ).any { normalized.contains(it) }
    }

    /**
     * The relationship inbox is for person-to-person context, not every SMS
     * surfaced by the phone. Some OEM messaging apps hide the actual sender
     * behind generic titles such as “信息”; in that case we fail closed instead
     * of persisting unattributable or broadcast content.
     */
    private fun isNonPersonalSms(sender: String?, body: String?): Boolean {
        val normalizedSender = sender.orEmpty().replace(Regex("\\s+"), "").lowercase()
        val normalizedBody = body.orEmpty().replace(Regex("\\s+"), " ").trim()
        if (normalizedSender in GENERIC_SMS_TITLES) return true
        if (normalizedSender.startsWith("106") || SERVICE_SHORT_CODE.matches(normalizedSender)) return true
        if (BRANDED_SMS.containsMatchIn(normalizedBody)) return true
        if (GENERIC_SMS_BODY.matches(normalizedBody)) return true
        return SMS_MARKETING_WORDS.any(normalizedBody::contains)
    }

    private val GROUP_MESSAGE = Regex("""^([^:：]{1,80})\s*[:：]\s*(.+)$""")
    internal const val REPLY_FOCUS_WINDOW_MS = 8 * 60 * 60_000L
    private val REPLY_INDICATORS = listOf(
        "收到",
        "明白",
        "确认",
        "看了",
        "已看",
        "已发",
        "已经发",
        "谢谢",
        "谢谢你",
        "谢谢啦",
        "多谢",
        "好的",
        "ok",
        "ok了",
    )
    private val REPLY_INDICATOR = Regex(
        "(?:" +
            REPLY_INDICATORS.joinToString("|") { Regex.escape(it) } + ")",
        RegexOption.IGNORE_CASE,
    )

    fun likelyReplySignal(text: String?): Boolean {
        val normalized = text.orEmpty().replace(Regex("\\s+"), "")
            .replace("，", "").replace("。", "").replace(",", "").replace("、", "")
        return normalized.isNotBlank() && REPLY_INDICATOR.containsMatchIn(normalized)
    }

    internal fun hasLikelyRecentOutboundContext(candidate: NotificationCandidateEntity): Boolean =
        (candidate.direction == "INCOMING") && likelyReplySignal(candidate.body)
    private val GENERIC_SMS_TITLES = setOf(
        "信息",
        "新信息",
        "短信",
        "新短信",
        "message",
        "messages",
        "newmessage",
    )
    private val SERVICE_SHORT_CODE = Regex("""(?:9[5-6]\d{3,6}|10\d{3,12}|100\d{2,8})""")
    private val BRANDED_SMS = Regex("""^[【\[].{1,40}[】\]]""")
    private val GENERIC_SMS_BODY = Regex("""(?:查看信息|查看短信|新信息|新短信|你有一条新信息)[。.!！]?""")
    private val SMS_MARKETING_WORDS = listOf("退订", "回复td", "回复 t", "广告")
}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
