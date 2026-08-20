package com.zhiban.rebuild.data.contact

import java.time.LocalDate
import java.time.YearMonth

internal fun nextImportantDateOccurrence(month: Int, day: Int, today: LocalDate): LocalDate? {
    if (month !in 1..12 || day !in 1..31) return null
    fun occurrence(year: Int): LocalDate {
        val safeDay = day.coerceAtMost(YearMonth.of(year, month).lengthOfMonth())
        return LocalDate.of(year, month, safeDay)
    }
    val thisYear = occurrence(today.year)
    return if (thisYear.isBefore(today)) occurrence(today.year + 1) else thisYear
}

internal fun importantDateDisplayLabel(kind: String): String = when (kind.uppercase()) {
    "BIRTHDAY" -> "生日"
    "ANNIVERSARY" -> "纪念日"
    else -> "重要日子"
}
