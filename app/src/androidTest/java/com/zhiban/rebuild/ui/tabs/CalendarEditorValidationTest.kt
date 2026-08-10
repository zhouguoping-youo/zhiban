package com.zhiban.rebuild.ui.tabs

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import com.zhiban.rebuild.ui.theme.ZhiBanTheme
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test

class CalendarEditorValidationTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun saveStaysDisabledUntilRequiredFieldsAreValid() {
        compose.setContent {
            ZhiBanTheme {
                ScheduleEditorDialog(
                    selectedDate = LocalDate.of(2026, 8, 9),
                    schedule = null,
                    onDismiss = {},
                    onSaveToSystem = {},
                    notificationsAllowed = true,
                    onRequestNotificationPermission = {},
                    onSaveWithReminder = { _, _, _, _, _, _, _, _, _ -> },
                )
            }
        }

        compose.onNodeWithText("保存").assertIsNotEnabled()
        compose.onNodeWithText("日程名称").performTextInput("项目复盘")
        compose.onNodeWithText("保存").assertIsEnabled()
    }

    @Test
    fun saveRemainsReachableAtTwoHundredPercentFontScale() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                ZhiBanTheme {
                    ScheduleEditorDialog(
                        selectedDate = LocalDate.of(2026, 8, 9),
                        schedule = null,
                        onDismiss = {},
                        onSaveToSystem = {},
                        notificationsAllowed = true,
                        onRequestNotificationPermission = {},
                        onSaveWithReminder = { _, _, _, _, _, _, _, _, _ -> },
                    )
                }
            }
        }

        compose.onNodeWithText("保存").assertIsDisplayed()
    }
}
