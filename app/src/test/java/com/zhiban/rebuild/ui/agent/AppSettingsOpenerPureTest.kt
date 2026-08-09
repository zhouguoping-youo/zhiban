package com.zhiban.rebuild.ui.agent

import org.junit.Assert.*
import org.junit.Test

/**
 * Pure-JVM tests for [AppSettingsOpener]'s URI string builder.
 *
 * The Intent/Context-touching parts are exercised at instrumentation time on a
 * real device. This test pins the URI string format used by Android system
 * settings so a future refactor cannot accidentally change the package URI
 * (which would cause "Settings keeps telling us the app isn't installed" UX).
 */
class AppSettingsOpenerPureTest {

    @Test fun `package details uri is well-formed scheme colon name`() {
        assertEquals(
            "package:com.zhiban.rebuild",
            AppSettingsOpener.packageDetailsUriString("com.zhiban.rebuild"),
        )
    }

    @Test fun `different packages produce different uri strings`() {
        val a = AppSettingsOpener.packageDetailsUriString("com.zhiban.rebuild")
        val b = AppSettingsOpener.packageDetailsUriString("com.zhiban.rebuild.debug")
        assertNotEquals(a, b)
        assertTrue(a.startsWith("package:"))
        assertTrue(b.startsWith("package:"))
    }

    @Test fun `uri string preserves the raw package name verbatim`() {
        // If we ever sanitize the package name we would silently break settings routing.
        val raw = "com.zhiban.rebuild"
        assertEquals("package:$raw", AppSettingsOpener.packageDetailsUriString(raw))
    }
}
