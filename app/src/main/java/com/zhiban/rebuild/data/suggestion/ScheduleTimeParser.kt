package com.zhiban.rebuild.data.suggestion

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

/** Single deterministic authority for notification and wake-up schedule time expressions. */
internal object ScheduleTimeParser {
    data class Resolution(val dateTime: LocalDateTime, val explicitDate: Boolean, val explicitTime: Boolean)

    fun resolve(text: String, nowEpochMs: Long, zoneId: ZoneId, allowTimeOnly: Boolean, defaultTimeForDate: Boolean): Resolution? = resolve(
        text = text,
        now = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(nowEpochMs), zoneId),
        allowTimeOnly = allowTimeOnly,
        defaultTimeForDate = defaultTimeForDate,
    )

    fun resolve(text: String, now: ZonedDateTime, allowTimeOnly: Boolean, defaultTimeForDate: Boolean): Resolution? {
        val date = resolveDate(text, now.toLocalDate())
        val time = resolveTime(text)
        if (NUMERIC_TIME_HINT.containsMatchIn(text) && time == null) return null
        if (date != null) {
            val resolvedTime = time ?: if (defaultTimeForDate) DEFAULT_DATE_ONLY_TIME else return null
            return Resolution(LocalDateTime.of(date, resolvedTime), explicitDate = true, explicitTime = time != null)
        }
        if (!allowTimeOnly || time == null) return null
        val today = now.toLocalDate()
        val candidate = LocalDateTime.of(today, time)
        val targetDate = if (candidate.isAfter(now.toLocalDateTime())) today else today.plusDays(1)
        return Resolution(LocalDateTime.of(targetDate, time), explicitDate = false, explicitTime = true)
    }

    private fun resolveDate(text: String, today: LocalDate): LocalDate? {
        DAY_WORDS.firstOrNull { text.contains(it.first) }?.let { return today.plusDays(it.second) }
        ABSOLUTE_DATE.find(text)?.let { match ->
            val month = match.groupValues[1].toIntOrNull()
            val day = match.groupValues[2].toIntOrNull() ?: return@let
            return if (month != null) resolveMonthDay(today, month, day) else resolveDayOfMonth(today, day)
        }
        WEEKDAY.find(text)?.let { match ->
            val target = weekday(match.groupValues[1]) ?: return@let
            var candidate = today.with(TemporalAdjusters.nextOrSame(target))
            if (candidate == today && ("下周" in text || "下星期" in text)) candidate = candidate.plusWeeks(1)
            return candidate
        }
        return null
    }

    private fun resolveMonthDay(today: LocalDate, month: Int, day: Int): LocalDate? = runCatching {
        var candidate = LocalDate.of(today.year, month, day)
        if (candidate.isBefore(today)) candidate = candidate.plusYears(1)
        candidate
    }.getOrNull()

    private fun resolveDayOfMonth(today: LocalDate, day: Int): LocalDate? {
        if (day !in 1..31) return null
        val currentMonth = runCatching { today.withDayOfMonth(day) }.getOrNull()
        if (currentMonth != null && !currentMonth.isBefore(today)) return currentMonth
        return runCatching { today.plusMonths(1).withDayOfMonth(day) }.getOrNull()
    }

    private fun resolveTime(text: String): LocalTime? {
        val marker = PERIOD_WORDS.firstOrNull(text::contains)
        HOUR_MINUTE.find(text)?.let { match ->
            val hour = match.groupValues[1].toIntOrNull() ?: return null
            val minute = match.groupValues[2].toIntOrNull() ?: return null
            return validatedTime(hour, minute, marker)
        }
        HOUR_HALF.find(text)?.let { match ->
            val hour = parseHour(match.groupValues[1]) ?: return null
            return validatedTime(hour, 30, marker)
        }
        HOUR_FULL.find(text)?.let { match ->
            val hour = parseHour(match.groupValues[1]) ?: return null
            val minute = match.groupValues[2].takeIf(String::isNotBlank)?.toIntOrNull() ?: 0
            return validatedTime(hour, minute, marker)
        }
        return null
    }

    private fun parseHour(value: String): Int? = value.toIntOrNull() ?: when (value) {
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

    private fun validatedTime(rawHour: Int, minute: Int, marker: String?): LocalTime? {
        if (rawHour !in 0..23 || minute !in 0..59) return null
        val hour = when {
            marker in AFTERNOON_PERIODS && rawHour in 1..11 -> rawHour + 12
            marker == "凌晨" && rawHour == 12 -> 0
            else -> rawHour
        }
        return runCatching { LocalTime.of(hour, minute) }.getOrNull()
    }

    private fun weekday(value: String): DayOfWeek? = when (value) {
        "一" -> DayOfWeek.MONDAY
        "二" -> DayOfWeek.TUESDAY
        "三" -> DayOfWeek.WEDNESDAY
        "四" -> DayOfWeek.THURSDAY
        "五" -> DayOfWeek.FRIDAY
        "六" -> DayOfWeek.SATURDAY
        "日", "天" -> DayOfWeek.SUNDAY
        else -> null
    }

    private val DAY_WORDS = listOf("大后天" to 3L, "后天" to 2L, "明天" to 1L, "明晚" to 1L, "明早" to 1L, "今天" to 0L, "今晚" to 0L)
    private val ABSOLUTE_DATE = Regex("(?<!\\d)(?:(\\d{1,2})\\s*月\\s*)?(\\d{1,2})\\s*[日号]")
    private val WEEKDAY = Regex("(?:本周|这周|下周|下星期|星期|周)\\s*([一二三四五六日天])")
    private val PERIOD_WORDS = listOf("大后天晚上", "明晚", "今晚", "凌晨", "早上", "上午", "中午", "下午", "晚上", "傍晚", "明早")
    private val AFTERNOON_PERIODS = setOf("中午", "下午", "晚上", "傍晚", "今晚", "明晚", "大后天晚上")
    private val HOUR_MINUTE = Regex("(?<!\\d)(\\d{1,2})[:：](\\d{1,2})(?!\\d)")
    private val HOUR_HALF = Regex("(?<!\\d)(\\d{1,2}|[零一二两三四五六七八九十]{1,2})\\s*点\\s*半")
    private val HOUR_FULL = Regex("(?<!\\d)(?<![有共这那几多])(\\d{1,2}|[零一二两三四五六七八九十]{1,2})\\s*点(?:\\s*(\\d{1,2})\\s*分)?(?![个条项点要\\d])")
    private val NUMERIC_TIME_HINT = Regex("(?<!\\d)\\d{1,2}\\s*(?:[:：]|点)")
    private val DEFAULT_DATE_ONLY_TIME = LocalTime.of(9, 0)
}
