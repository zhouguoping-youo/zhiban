package com.zhiban.rebuild.ui.agent.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.runtime.config.AgentControlStore
import com.zhiban.rebuild.runtime.memory.AgentMemorySettingsService
import com.zhiban.rebuild.ui.theme.ZhiBanTheme
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentMemoryPageTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var database: AgentDatabase

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences("agent_controls", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        database = Room.inMemoryDatabaseBuilder(context, AgentDatabase::class.java).build()
        val controls = AgentControlStore(context)
        compose.setContent {
            ZhiBanTheme {
                AgentMemoryPage(onBack = {}, viewModel = AgentMemoryViewModel(AgentMemorySettingsService(database), controls))
            }
        }
    }

    @After fun tearDown() = database.close()

    @Test fun mainPageShowsTwoSimplifiedToggles() {
        compose.onNodeWithText("记住对话内容").assertIsDisplayed()
        compose.onNodeWithText("自动发现新记忆").assertIsDisplayed()
    }

    @Test fun temporaryModeHiddenUntilAdvancedExpanded() {
        compose.onNodeWithText("临时对话").assertDoesNotExist()
        compose.onNodeWithText("高级").performClick()
        compose.onNodeWithText("临时对话").assertIsDisplayed()
    }
}
