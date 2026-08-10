package com.zhiban.rebuild.ui.agent.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhiban.rebuild.runtime.config.AgentControlStore
import com.zhiban.rebuild.runtime.config.ExecutionPreference
import com.zhiban.rebuild.runtime.personalization.AgentPersonalizationStore
import com.zhiban.rebuild.runtime.personalization.ResponseStyle
import com.zhiban.rebuild.runtime.personalization.UserProfileStore
import com.zhiban.rebuild.ui.theme.ZhiBanTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentPersonalizationPageTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var store: AgentPersonalizationStore
    private lateinit var controls: AgentControlStore
    private lateinit var userProfile: UserProfileStore

    @Before fun setUp() {
        store = AgentPersonalizationStore(ApplicationProvider.getApplicationContext())
        controls = AgentControlStore(ApplicationProvider.getApplicationContext())
        controls.saveExecution(ExecutionPreference.BALANCED)
        userProfile = UserProfileStore(ApplicationProvider.getApplicationContext())
        userProfile.clear()
        render()
    }

    private fun render() {
        compose.setContent {
            ZhiBanTheme {
                AgentPersonalizationPage(onBack = {}, viewModel = AgentPersonalizationViewModel(store, controls, userProfile))
            }
        }
    }

    @Test fun showsAllSevenStyleCards() {
        compose.onNodeWithText("回答偏好").assertIsDisplayed()
        compose.onNodeWithText("表达风格").assertIsDisplayed()
        compose.onNodeWithText("思考深度").assertIsDisplayed()
        compose.onNodeWithText("简洁").assertIsDisplayed()
        compose.onNodeWithText("平衡").assertIsDisplayed()
        compose.onNodeWithText("轻松").assertIsDisplayed()
        compose.onNodeWithText("自定义").assertIsDisplayed()
        compose.onNodeWithText("像朋友聊天，随意自然").assertIsDisplayed()
    }

    @Test fun styleAndThinkingDepthPersistTogetherOnOneSave() {
        compose.onNodeWithText("专业").performScrollTo().performClick()
        compose.onNodeWithTag("answer_preference_list").performScrollToNode(hasText("深入"))
        compose.onNodeWithText("深入").performClick()
        compose.onNodeWithTag("answer_preference_list").performScrollToNode(hasText("保存"))
        compose.onNodeWithText("保存").performClick()
        compose.waitForIdle()

        assertEquals(ResponseStyle.PROFESSIONAL, store.load().style)
        assertEquals(ExecutionPreference.DEEP, controls.execution())
    }

    @Test fun selectingCustomRevealsInstructionFieldAndSavePersists() {
        // Custom instruction field hidden until CUSTOM is selected.
        compose.onNodeWithText("给知伴的指令").assertDoesNotExist()
        compose.onNodeWithText("自定义").performClick()
        compose.onNodeWithText("给知伴的指令").assertIsDisplayed()

        compose.onNodeWithTag("answer_preference_list").performScrollToNode(hasText("保存"))
        compose.onNodeWithText("保存").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("已保存").assertIsDisplayed()
        assertEquals(ResponseStyle.CUSTOM, store.load().style)
    }

    @Test fun selectingPresetStylePersistsAfterSave() {
        compose.onNodeWithText("专业").performScrollTo().performClick()
        compose.onNodeWithTag("answer_preference_list").performScrollToNode(hasText("保存"))
        compose.onNodeWithText("保存").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("已保存").assertIsDisplayed()
        assertEquals(ResponseStyle.PROFESSIONAL, store.load().style)
    }
}
