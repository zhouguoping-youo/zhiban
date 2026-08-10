package com.zhiban.rebuild.ui.tabs

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import com.zhiban.rebuild.ui.theme.ZhiBanTheme
import org.junit.Rule
import org.junit.Test

class ContactEditorAccessibilityTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun saveRemainsReachableAtTwoHundredPercentFontScale() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                ZhiBanTheme {
                    ContactEditorDialog(
                        contact = null,
                        onDismiss = {},
                        onSave = { _, _, _, _, _, _, _, _, _ -> },
                    )
                }
            }
        }

        compose.onNodeWithText("保存").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun saveIsDisabledUntilAContactNameIsEntered() {
        compose.setContent {
            ZhiBanTheme {
                ContactEditorDialog(
                    contact = null,
                    onDismiss = {},
                    onSave = { _, _, _, _, _, _, _, _, _ -> },
                )
            }
        }

        compose.onNodeWithText("保存").assertIsNotEnabled()
        compose.onNodeWithText("姓名").performTextInput("王小明")
        compose.onNodeWithText("保存").assertIsEnabled()
    }
}
