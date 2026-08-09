package com.zhiban.rebuild.runtime.config

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentControlStoreTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var controls: AgentControlStore

    @Before fun setUp() {
        context.getSharedPreferences("agent_controls", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        controls = AgentControlStore(context)
    }

    @After fun tearDown() {
        context.getSharedPreferences("agent_controls", android.content.Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun repeatedNegativeFeedbackCreatesReviewableSuggestionButNeverChangesSettings() {
        controls.recordHumanFeedback(false)
        controls.recordHumanFeedback(false)
        assertNull(controls.pendingImprovement())
        controls.recordHumanFeedback(false)
        assertEquals("concise_after_negative_feedback", controls.pendingImprovement()?.id)
        assertEquals(ExecutionPreference.BALANCED, controls.execution())
        controls.dismissImprovement()
        assertNull(controls.pendingImprovement())
    }

    @Test fun disabledToolPersistsAndCanBeReenabled() {
        controls.saveToolEnabled("memory.search", false)
        assertTrue(!controls.isToolEnabled("memory.search"))
        controls.saveToolEnabled("memory.search", true)
        assertTrue(controls.isToolEnabled("memory.search"))
    }
}
