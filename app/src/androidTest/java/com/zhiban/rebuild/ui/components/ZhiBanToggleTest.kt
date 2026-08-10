package com.zhiban.rebuild.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.zhiban.rebuild.ui.theme.ZhiBanTheme
import org.junit.Rule
import org.junit.Test

class ZhiBanToggleTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun toggleRowHasOneConsistentTouchTargetAndTogglesFromTheWholeRow() {
        compose.setContent {
            var checked by remember { mutableStateOf(false) }
            ZhiBanTheme {
                ZhiBanToggleRow(
                    title = "同步消息",
                    subtitle = "从新消息中发现线索",
                    checked = checked,
                    onCheckedChange = { checked = it },
                    modifier = Modifier.testTag("toggle-row"),
                )
            }
        }

        compose.onNodeWithTag("toggle-row")
            .assertIsToggleable()
            .assertIsOff()
            .assertHeightIsAtLeast(72.dp)
            .performClick()
            .assertIsOn()
    }

    @Test
    fun standaloneSwitchKeepsAndroidMinimumTouchHeight() {
        compose.setContent {
            ZhiBanTheme {
                ZhiBanSwitch(
                    checked = true,
                    onCheckedChange = {},
                    modifier = Modifier.testTag("switch"),
                )
            }
        }

        compose.onNodeWithTag("switch")
            .assertHeightIsAtLeast(48.dp)
        compose.onAllNodes(isToggleable()).onFirst().assertIsOn()
    }
}
