package com.zhiban.rebuild.ui.theme

import java.time.format.DateTimeFormatter

/**
 * Shared Chinese-locale date/time formatters. Centralizing the patterns gives a
 * single place to change the convention. The zero-padded and non-padded month/day
 * forms render differently (08月07日 vs 8月7日), so they stay as distinct constants —
 * call sites keep their existing rendering.
 */
object DateFormats {
    /** Zero-padded, e.g. 08月07日 15:30 */
    val MonthDayTimePadded: DateTimeFormatter = DateTimeFormatter.ofPattern("MM月dd日 HH:mm")

    /** Non-padded, e.g. 8月7日 15:30 */
    val MonthDayTime: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日 HH:mm")

    /** e.g. 15:30 */
    val Time: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    /** e.g. 2026年 8月 */
    val YearMonth: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy年 M月")
}
