package com.zhiban.rebuild.ui.agent

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
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
            "回答偏好" to "回答偏好",
            "工具" to "工具",
            "技能" to "技能",
            "回答反馈" to "回答反馈",
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
        // Target the 创建日程 tool's own switch via a text+toggleable selector that stays stable
        // across the toggle — not "the first switch", which is now the web-search opt-in switch.
        val toolSwitch = compose.onAllNodes(isToggleable() and hasText("创建日程"))[0]
        toolSwitch.assertIsOn().performClick().assertIsOff()
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

    @Test fun dataManagementExposesPortableBackupAndExplainsCredentialBoundary() {
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("我的").performClick()
        compose.onNodeWithText("数据管理").performScrollTo().performClick()

        compose.onNodeWithText("创建加密备份").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("从加密备份恢复").assertIsDisplayed()
        compose.onNodeWithText("含联系人、关系、日程、记忆和 CRM；不含 API Key").assertIsDisplayed()
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

    @Test fun settingsPrioritizeUsefulPermissionsAndRemoveTechnicalDuplicates() {
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("我的").performClick()

        compose.onNodeWithText("通知").performScrollTo().performClick()
        compose.onNodeWithText("系统通知").assertIsDisplayed()
        compose.onNodeWithText("打开系统通知设置").assertDoesNotExist()
        compose.onNodeWithContentDescription("返回").performClick()

        compose.onNodeWithText("隐私与权限").performScrollTo().performClick()
        compose.onNodeWithText("让知伴更好用").assertIsDisplayed()
        compose.onNodeWithText("联系人").assertIsDisplayed()
        compose.onNodeWithText("日历").assertIsDisplayed()
        compose.onNodeWithText("数据发送范围").assertDoesNotExist()
        compose.onNodeWithText("模型数据发送").assertDoesNotExist()
        compose.onNodeWithText("允许远程语义检索").assertDoesNotExist()
        compose.onNodeWithContentDescription("返回").performClick()

        compose.onNodeWithText("存储").performScrollTo().performClick()
        compose.onNodeWithText("保存位置").assertIsDisplayed()
        compose.onNodeWithText("应用专属目录").assertIsDisplayed()
        compose.onNodeWithText("共计").assertIsDisplayed()
    }

    @Test fun answerModesExplainTheirRealRuntimeTradeoffs() {
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("我的").performClick()
        compose.onNodeWithText("智能体设置").performClick()
        compose.onNodeWithText("回答偏好").performScrollTo().performClick()

        compose.onNodeWithText("表达风格").assertIsDisplayed()
        compose.onNodeWithText("思考深度").assertIsDisplayed()
        compose.onNodeWithText("对话风格").assertDoesNotExist()
        compose.onNodeWithText("回答方式").assertDoesNotExist()

        compose.onNodeWithText("简单问答 · 检索更少，响应更快").assertIsDisplayed()
        compose.onNodeWithText("日常任务 · 自动平衡速度与信息量").assertIsDisplayed()
        compose.onNodeWithText("复杂问题 · 检索更多上下文，耗时更长").assertIsDisplayed().performClick()
        compose.onNodeWithTag("answer_preference_list").performScrollToNode(hasText("保存"))
        compose.onNodeWithText("保存").performClick()
        assert(
            compose.activity.getSharedPreferences("agent_controls", android.content.Context.MODE_PRIVATE)
                .getString("execution_preference", null) == "DEEP",
        )
    }
}
