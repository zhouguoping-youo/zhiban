package com.zhiban.rebuild.runtime.network

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AndroidNetworkQualityGatewayTest {
    @Test fun partialButInternetCapableEmulatorIsNotMisclassifiedOffline() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertNotEquals(NetworkQuality.OFFLINE, AndroidNetworkQualityGateway(context).current())
    }
}
