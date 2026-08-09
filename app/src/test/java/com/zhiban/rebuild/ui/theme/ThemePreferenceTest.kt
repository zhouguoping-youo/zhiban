package com.zhiban.rebuild.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class ThemePreferenceTest {
    @Test
    fun unknownStorageValueFallsBackToSystem() {
        assertEquals(ThemePreference.SYSTEM, ThemePreference.fromStorage("unknown"))
        assertEquals(ThemePreference.SYSTEM, ThemePreference.fromStorage(null))
        assertEquals(ThemePreference.SYSTEM, ThemePreference.fromStorage(""))
    }

    @Test
    fun everyStoredValueRoundTrips() {
        ThemePreference.entries.forEach { preference ->
            assertEquals(preference, ThemePreference.fromStorage(preference.storageValue))
        }
    }

    @Test
    fun defaultStorageValueIsSystem() {
        assertEquals("system", ThemePreference.DEFAULT_STORAGE_VALUE)
        assertEquals(ThemePreference.SYSTEM, ThemePreference.fromStorage(ThemePreference.DEFAULT_STORAGE_VALUE))
    }
}
