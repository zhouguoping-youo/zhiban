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

    @Test fun `embedding connection never exposes provider failure details`() {
        assertEquals(
            "请输入有效的模型或接入点 ID。",
            embeddingConfigurationFailureMessage("EMBEDDING_MODEL_INVALID"),
        )
        assertEquals(
            "语义检索连接失败，请检查 API Key 和接入点 ID。",
            embeddingConfigurationFailureMessage("EMBEDDING_HTTP_401 secret-provider-body"),
        )
    }
}
