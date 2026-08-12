package com.zhiban.rebuild.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelConfigFailureMessageTest {
    @Test fun `model connection reports actionable safe failure messages`() {
        assertEquals(
            "当前设备无法连接网络，请检查网络或 DNS 后重试。",
            providerConfigurationFailureMessage("NETWORK_OFFLINE"),
        )
        assertEquals(
            "API Key 无效或已失效，请检查后重试。",
            providerConfigurationFailureMessage("AUTHENTICATION_FAILED"),
        )
        assertEquals(
            "连接超时，请切换网络后重试。",
            providerConfigurationFailureMessage("TIMEOUT"),
        )
    }
}
