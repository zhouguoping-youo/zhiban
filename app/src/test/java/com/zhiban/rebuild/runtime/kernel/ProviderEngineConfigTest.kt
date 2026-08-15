package com.zhiban.rebuild.runtime.kernel

import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderEngineConfigTest {
    @Test fun defaultRerankBudgetAllowsNormalNetworkLatency() {
        assertTrue(ProviderEngineConfig().rerankTimeoutMs >= MIN_REALISTIC_RERANK_TIMEOUT_MS)
    }

    private companion object {
        const val MIN_REALISTIC_RERANK_TIMEOUT_MS = 1_500L
    }
}
