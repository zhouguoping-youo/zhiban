package com.zhiban.rebuild.runtime.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionPreferenceConfigTest {
    @Test
    fun `fast forces FTS-only and disables LLM rerank`() {
        val config = AgentDynamicConfig().withExecutionPreference(ExecutionPreference.FAST)

        assertTrue(config.forceFtsOnly)
        assertFalse(config.enableLlmRerank)
        assertEquals(ExecutionPreference.FAST, config.executionPreference)
    }

    @Test
    fun `balanced keeps default retrieval behaviour`() {
        val base = AgentDynamicConfig()
        val config = base.withExecutionPreference(ExecutionPreference.BALANCED)

        assertFalse(config.forceFtsOnly)
        assertTrue(config.enableLlmRerank)
        assertEquals(base.maxContextTokens, config.maxContextTokens)
        assertEquals(AgentDynamicConfig.DEFAULT_RECALL_LIMIT, config.retrievalRecallLimit)
    }

    @Test
    fun `deep raises context window and recall limit`() {
        val base = AgentDynamicConfig()
        val config = base.withExecutionPreference(ExecutionPreference.DEEP)

        assertFalse(config.forceFtsOnly)
        assertEquals(base.maxContextTokens * 3 / 2, config.maxContextTokens)
        assertEquals(AgentDynamicConfig.DEEP_RECALL_LIMIT, config.retrievalRecallLimit)
    }

    @Test
    fun `three preferences produce distinct effective configs`() {
        val base = AgentDynamicConfig()
        val fast = base.withExecutionPreference(ExecutionPreference.FAST)
        val balanced = base.withExecutionPreference(ExecutionPreference.BALANCED)
        val deep = base.withExecutionPreference(ExecutionPreference.DEEP)

        assertTrue(fast.forceFtsOnly != balanced.forceFtsOnly)
        assertTrue(deep.maxContextTokens > balanced.maxContextTokens)
        assertTrue(deep.retrievalRecallLimit > balanced.retrievalRecallLimit)
    }

    @Test
    fun `deep max context tokens stays within allowed bounds`() {
        assertEquals(128_000, AgentDynamicConfig.deepMaxContextTokens(100_000))
        assertEquals(1_500, AgentDynamicConfig.deepMaxContextTokens(1_000))
    }
}
