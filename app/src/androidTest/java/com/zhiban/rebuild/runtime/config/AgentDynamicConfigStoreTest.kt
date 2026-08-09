package com.zhiban.rebuild.runtime.config

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentDynamicConfigStoreTest {
    private lateinit var context: Context
    private lateinit var store: AgentDynamicConfigStore

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("agent_remote_config", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("agent_feature_overrides", Context.MODE_PRIVATE).edit().clear().commit()
        store = AgentDynamicConfigStore(context)
    }

    @Test fun defaultsThenRemoteThenUserOverrideHaveDeterministicPrecedence() {
        assertTrue(store.snapshot().enableHybridRetrieval)
        store.applyRemote(
            AgentDynamicConfig(
                forceFtsOnly = true, llmTimeoutSeconds = 2, maxContextTokens = 200,
                disabledSkills = setOf("calendar_coordination", "bad id!"),
                providerBlacklist = setOf("minimax"), enableHybridRetrieval = false,
                enableLlmRerank = false, enableAgentUndo = false, enableMcpRemote = false,
            ),
        )
        val remote = store.snapshot()
        assertTrue(remote.forceFtsOnly)
        assertEquals(5, remote.llmTimeoutSeconds)
        assertEquals(1_000, remote.maxContextTokens)
        assertEquals(setOf("calendar_coordination"), remote.disabledSkills)
        assertEquals(setOf("minimax"), remote.providerBlacklist)
        assertFalse(remote.enableHybridRetrieval)

        store.setUserOverride("enable_hybrid_retrieval", true)
        assertTrue(store.snapshot().enableHybridRetrieval)
        store.setUserOverride("enable_hybrid_retrieval", null)
        assertFalse(store.snapshot().enableHybridRetrieval)
    }

    @Test fun unknownUserOverrideCannotCreateUncontrolledFlag() {
        assertTrue(runCatching { store.setUserOverride("disable_text_runtime", true) }.isFailure)
        assertTrue(store.snapshot().enableMcpRemote)
    }
}
