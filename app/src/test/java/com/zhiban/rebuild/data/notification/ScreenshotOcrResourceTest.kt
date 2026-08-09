package com.zhiban.rebuild.data.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenshotOcrResourceTest {
    @Test
    fun synchronousOcrStartFailureReleasesCapturedResourceExactlyOnce() {
        var releases = 0

        val result = startResourceBoundOperation<String>(
            start = { error("OCR_START_FAILED") },
            releaseOnFailure = { releases += 1 },
        )

        assertNull(result)
        assertEquals(1, releases)
    }
}
