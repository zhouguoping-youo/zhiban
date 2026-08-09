package com.zhiban.rebuild.ui.agent

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhiban.rebuild.MainActivity
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentSettingsNavigationE2ETest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @After fun cleanup() {
        compose.activity.getSharedPreferences(
            "agent_controls",
            android.content.Context.MODE_PRIVATE,
        ).edit().clear().commit()
    }

    @Test fun everyAgentSettingsEntryOpensAndBackReturnsThenToolTogglePersists() {
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("我的").performClick()
        compose.onNodeWithText("智能体设置").performClick()
        compose.onNodeWithText("智能体设置").assertIsDisplayed()

        listOf(
            "大模型连接" to "大模型连接",
            "记忆" to "记忆",
            "对话风格" to "对话风格",
            "工具" to "工具",
            "技能" to "技能",
            "行为与安全" to "行为与安全",
            "反馈与改进" to "反馈与改进",
            "运行记录" to "运行记录",
        ).forEach { (entry, title) ->
            compose.onNodeWithText(entry).performScrollTo().performClick()
            compose.onNodeWithText(title).assertExists()
            compose.onNodeWithContentDescription("返回").assertIsDisplayed()
            compose.onNodeWithContentDescription("返回").performClick()
            compose.onNodeWithText("智能体设置").assertIsDisplayed()
        }

        compose.onNodeWithText("工具").performScrollTo().performClick()
        compose.onNodeWithText("添加").performClick()
        compose.onNodeWithText("添加外部 MCP 服务").assertIsDisplayed()
        compose.onNodeWithText("服务 ID，如 feishu").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("创建日程").assertIsDisplayed()
        val firstSwitch = compose.onAllNodes(isToggleable())[0]
        firstSwitch.assertIsOn().performClick().assertIsOff()
        assertFalse(
            compose.activity.getSharedPreferences("agent_controls", android.content.Context.MODE_PRIVATE)
                .getStringSet("disabled_tools", emptySet()).orEmpty().isEmpty(),
        )
    }

    @Test fun profileTabDropsLanguageAndResetEntries() {
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("我的").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("语言").assertDoesNotExist()
        compose.onNodeWithText("重置知伴").assertDoesNotExist()
        compose.onNodeWithText("数据管理").assertExists()
    }

    @Test fun everyProfileSettingsEntryOpensAndBackReturns() {
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("我的").performClick()

        listOf(
            "智能体设置",
            "自动整理",
            "外观",
            "通知",
            "隐私与权限",
            "存储",
            "数据管理",
            "报告问题",
            "关于知伴",
        ).forEach { title ->
            compose.onNodeWithText(title).performScrollTo().performClick()
            compose.onNodeWithText(title).assertIsDisplayed()
            compose.onNodeWithContentDescription("返回").performClick()
            compose.onNodeWithContentDescription("我的").assertExists()
        }
    }

    @Test fun appearancePageOffersThreeThemeChoices() {
        compose.activity.getSharedPreferences("theme_preference", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("我的").performClick()
        compose.onNodeWithText("外观").performClick()

        compose.onNodeWithText("浅色").assertIsDisplayed()
        compose.onNodeWithText("深色").assertIsDisplayed()
        compose.onNodeWithText("跟随系统").assertIsDisplayed()

        compose.onNodeWithText("深色").performClick()
        assert(
            compose.activity.getSharedPreferences("theme_preference", android.content.Context.MODE_PRIVATE)
                .getString("theme_preference", null) == "dark",
        )
        compose.onNodeWithText("跟随系统").performClick()
        assert(
            compose.activity.getSharedPreferences("theme_preference", android.content.Context.MODE_PRIVATE)
                .getString("theme_preference", null) == "system",
        )
        compose.activity.getSharedPreferences("theme_preference", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
    }
}
