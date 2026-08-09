package com.zhiban.rebuild.runtime.network

import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkQualityTest {
    @Test fun onlyMissingInternetCapabilityIsOffline() {
        assertEquals(NetworkQuality.OFFLINE, classifyNetworkQuality(false, false, 0))
        assertEquals(NetworkQuality.WEAK, classifyNetworkQuality(true, false, 4_300))
    }

    @Test fun validatedNetworksRespectBandwidthGrades() {
        assertEquals(NetworkQuality.EXTREME, classifyNetworkQuality(true, true, 127))
        assertEquals(NetworkQuality.WEAK, classifyNetworkQuality(true, true, 999))
        assertEquals(NetworkQuality.NORMAL, classifyNetworkQuality(true, true, 1_000))
    }
}
