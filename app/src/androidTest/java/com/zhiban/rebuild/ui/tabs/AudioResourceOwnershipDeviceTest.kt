package com.zhiban.rebuild.ui.tabs

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudioResourceOwnershipDeviceTest {
    @Test
    fun failedRecorderInitializationReleasesItsOwnedResourceOnAndroid() {
        var releases = 0

        val result = acquireStartedResource(
            create = { "recorder" },
            start = { throw IllegalStateException("prepare failed") },
            release = { releases += 1 },
        )

        assertTrue(result.isFailure)
        assertEquals(1, releases)
    }
}
