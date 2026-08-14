package com.zhiban.rebuild.ui.tabs

import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CrmMoneyLogicTest {
    @Test fun minorUnitsKeepCentsWhenDisplayed() {
        assertEquals("¥123.45", formatCrmMoney(12_345L, "CNY", Locale.US))
        assertEquals("USD 1,000", formatCrmMoney(100_000L, "USD", Locale.US))
        assertEquals("USD 1,000", formatCrmMoney(100_000L, "USD", Locale.CHINA))
    }

    @Test fun majorUnitInputConvertsExactlyWithoutOverflowOrTruncation() {
        assertEquals(12_345L, parseCrmMoneyMinor("123.45"))
        assertEquals(12_300L, parseCrmMoneyMinor("123.0"))
        assertNull(parseCrmMoneyMinor("123.456"))
        assertNull(parseCrmMoneyMinor("Infinity"))
        assertNull(parseCrmMoneyMinor("999999999999999999999999"))
    }

    @Test fun crmDatesFollowTheRequestedLocaleAndZone() {
        val zone = ZoneId.of("Asia/Shanghai")
        val epochMs = LocalDateTime.of(2026, 8, 15, 22, 0).atZone(zone).toInstant().toEpochMilli()

        assertEquals("Aug 15, 2026, 10:00 PM", formatCrmDateTime(epochMs, Locale.US, zone))
        assertEquals("2026年8月15日 22:00", formatCrmDateTime(epochMs, Locale.CHINA, zone))
        assertEquals("Aug 15, 2026", formatCrmDate(epochMs, Locale.US, zone))
        assertEquals("2026年8月15日", formatCrmDate(epochMs, Locale.CHINA, zone))
    }
}
