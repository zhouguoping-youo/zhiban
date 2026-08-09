package com.zhiban.rebuild.ui.tabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CrmMoneyLogicTest {
    @Test fun minorUnitsKeepCentsWhenDisplayed() {
        assertEquals("¥123.45", formatCrmMoney(12_345L, "CNY"))
        assertEquals("USD 1,000", formatCrmMoney(100_000L, "USD"))
    }

    @Test fun majorUnitInputConvertsExactlyWithoutOverflowOrTruncation() {
        assertEquals(12_345L, parseCrmMoneyMinor("123.45"))
        assertEquals(12_300L, parseCrmMoneyMinor("123.0"))
        assertNull(parseCrmMoneyMinor("123.456"))
        assertNull(parseCrmMoneyMinor("Infinity"))
        assertNull(parseCrmMoneyMinor("999999999999999999999999"))
    }
}
