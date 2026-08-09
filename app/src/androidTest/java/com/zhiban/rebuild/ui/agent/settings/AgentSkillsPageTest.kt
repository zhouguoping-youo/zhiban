package com.zhiban.rebuild.ui.agent.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhiban.agent.skills.BuiltInSkills
import com.zhiban.rebuild.runtime.config.AgentControlStore
import com.zhiban.rebuild.ui.theme.ZhiBanTheme
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentSkillsPageTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var controls: AgentControlStore

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences("agent_controls", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        controls = AgentControlStore(context)
        compose.setContent {
            ZhiBanTheme {
                AgentSkillsPage(onBack = {}, viewModel = AgentSkillsViewModel(controls))
            }
        }
    }

    @Test fun showsSkillCardsWithTaglinesAndToolCounts() {
        // First card composed on screen shows name and tagline; tool-count rows are rendered.
        compose.onNodeWithText("日程协调").assertIsDisplayed()
        compose.onNodeWithText("协调日程：先核对时间，创建修改前与你确认").assertIsDisplayed()
        compose.onNodeWithText("含 19 个工具").assertIsDisplayed()
    }

    @Test fun togglingSkillPersistsDisabled() {
        compose.waitForIdle()
        compose.onAllNodes(androidx.compose.ui.test.isToggleable()).onFirst().performClick()
        compose.waitForIdle()
        val disabled = ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSharedPreferences("agent_controls", android.content.Context.MODE_PRIVATE)
            .getStringSet("disabled_skills", emptySet()).orEmpty()
        assertFalse(disabled.isEmpty())
    }
}
