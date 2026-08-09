package com.zhiban.rebuild.ui.tabs

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
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
}
