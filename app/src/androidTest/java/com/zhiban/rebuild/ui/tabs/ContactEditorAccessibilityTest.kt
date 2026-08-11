package com.zhiban.rebuild.ui.tabs

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.zhiban.rebuild.ui.theme.ZhiBanTheme
import kotlin.math.abs
import org.junit.Assert.assertTrue
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

    @Test
    fun relationshipChoicesUseOneCenteredFortyEightDpControl() {
        compose.setContent {
            ZhiBanTheme {
                ContactEditorDialog(
                    contact = null,
                    onDismiss = {},
                    onSave = { _, _, _, _, _, _, _, _, _ -> },
                )
            }
        }

        val workChip = compose.onNodeWithTag("contact-relation-chip-工作").performScrollTo()
        val friendChip = compose.onNodeWithTag("contact-relation-chip-朋友").performScrollTo()
        val workBounds = workChip.getUnclippedBoundsInRoot()
        val friendBounds = friendChip.getUnclippedBoundsInRoot()
        val workTextBounds = compose.onNodeWithText("工作", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val workHeight = workBounds.bottom - workBounds.top
        val friendHeight = friendBounds.bottom - friendBounds.top

        assertTrue("all relationship choices must share one height", workHeight == friendHeight)
        assertTrue("relationship choices must keep a 48dp touch target", workHeight >= 48.dp)
        assertTrue(
            "choice text must be optically centered",
            abs(((workBounds.top + workBounds.bottom) / 2 - (workTextBounds.top + workTextBounds.bottom) / 2).value) < 1f,
        )

        friendChip.assertIsSelected()
        workChip.performClick().assertIsSelected()
    }
}
