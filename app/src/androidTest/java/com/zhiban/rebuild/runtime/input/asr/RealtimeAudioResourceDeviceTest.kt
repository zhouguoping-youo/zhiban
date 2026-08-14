package com.zhiban.rebuild.runtime.input.asr

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RealtimeAudioResourceDeviceTest {
    @Test
    fun twentyRealtimeSessionCleanupsReleaseEveryOwnedResourceOnAndroid() {
        var releases = 0

        repeat(20) {
            assertTrue(releaseRealtimeResource("audio:release", {}) { releases += 1 })
        }

        assertEquals(20, releases)
    }
}
