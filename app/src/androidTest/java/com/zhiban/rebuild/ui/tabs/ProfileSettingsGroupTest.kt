package com.zhiban.rebuild.ui.tabs

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.zhiban.rebuild.ui.theme.ZhiBanTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ProfileSettingsGroupTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun compactRowsKeepEverySettingNamedAndClickable() {
        var appearanceClicks = 0
        var notificationClicks = 0
        compose.setContent {
            ZhiBanTheme {
                ProfileSettingsGroup(
                    title = "设置",
                    items = listOf(
                        ProfileSettingItem(
                            Icons.Outlined.DarkMode,
                            "外观",
                            "跟随手机显示设置",
                            onClick = { appearanceClicks++ },
                        ),
                        ProfileSettingItem(
                            Icons.Outlined.NotificationsNone,
                            "通知",
                            "提醒和消息权限",
                            statusText = "2 条待处理",
                            onClick = { notificationClicks++ },
                        ),
                    ),
                )
            }
        }

        compose.onNodeWithText("跟随手机显示设置").assertDoesNotExist()
        compose.onNodeWithText("2 条待处理").assertIsDisplayed()
        compose.onNodeWithContentDescription("外观，跟随手机显示设置").performClick()
        compose.onNodeWithContentDescription("通知，2 条待处理，提醒和消息权限").performClick()

        compose.runOnIdle {
            assertEquals(1, appearanceClicks)
            assertEquals(1, notificationClicks)
        }
    }
}
