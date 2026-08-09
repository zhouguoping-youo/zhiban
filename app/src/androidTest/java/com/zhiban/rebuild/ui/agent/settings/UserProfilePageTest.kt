package com.zhiban.rebuild.ui.agent.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhiban.rebuild.runtime.personalization.UserProfileStore
import com.zhiban.rebuild.ui.theme.ZhiBanTheme
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UserProfilePageTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var store: UserProfileStore
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val suffix = System.nanoTime().toString()
    private val preferencesName = "user_profile_page_test_$suffix"
    private val avatarDirectoryName = "avatar_page_test_$suffix"

    @Before fun setUp() {
        store = UserProfileStore(context, preferencesName, avatarDirectoryName)
        store.clear()
    }

    @After fun cleanUp() {
        context.deleteSharedPreferences(preferencesName)
        File(context.filesDir, avatarDirectoryName).deleteRecursively()
    }

    private fun render() {
        compose.setContent {
            ZhiBanTheme {
                UserProfilePage(onBack = {}, viewModel = UserProfileViewModel(store))
            }
        }
    }

    @Test fun showsProfileSectionsAndOccupationChips() {
        render()
        compose.onNodeWithText("个人资料").assertIsDisplayed()
        compose.onNodeWithText("基本信息").assertExists()
        compose.onNodeWithText("联系方式").assertExists()
        // Occupation multi-select options are present.
        compose.onNodeWithText("技术").assertExists()
        compose.onNodeWithText("自由职业").assertExists()
        // Instructions section is below the fold; scroll the list until it is composed.
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("给知伴的指令"))
        compose.onNodeWithText("给知伴的指令").assertExists()
    }

    @Test fun addAccountShowsPlatformPicker() {
        render()
        compose.onNodeWithText("添加更多账号（飞书/企微/钉钉/QQ）").performClick()
        compose.onNodeWithText("选择平台").assertIsDisplayed()
        compose.onAllNodesWithText("飞书").onFirst().assertIsDisplayed()
        compose.onNodeWithText("钉钉").assertIsDisplayed()
    }
}
