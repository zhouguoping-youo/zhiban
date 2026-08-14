package com.zhiban.rebuild.ui.tabs

import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class CalendarLocaleFormattingTest {
    private val today = LocalDate.of(2026, 8, 15)

    @Test fun relativeDayHeadingsStayStable() {
        assertEquals("今天", dayHeading(today, today, Locale.US))
        assertEquals("明天", dayHeading(today.plusDays(1), today, Locale.US))
        assertEquals("昨天", dayHeading(today.minusDays(1), today, Locale.US))
    }

    @Test fun absoluteDayHeadingsAndWeekdaysFollowLocale() {
        val date = LocalDate.of(2026, 8, 17)

        assertEquals("Monday, August 17, 2026", dayHeading(date, today, Locale.US))
        assertEquals("2026年8月17日星期一", dayHeading(date, today, Locale.CHINA))
        assertEquals("M", weekdayNarrow(date, Locale.US))
        assertEquals("一", weekdayNarrow(date, Locale.CHINA))
    }
}
