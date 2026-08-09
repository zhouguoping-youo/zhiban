package com.zhiban.rebuild.ui.tabs

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import com.zhiban.rebuild.ui.theme.ZhiBanTheme
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test

class CalendarTouchTargetTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun monthDatesExposeFullDateAndFortyEightDpTouchTarget() {
        compose.setContent {
            ZhiBanTheme {
                MonthGrid(selected = LocalDate.of(2026, 8, 9), onSelect = {})
            }
        }

        compose.onNodeWithContentDescription("选择 2026-08-09")
            .assertHeightIsAtLeast(48.dp)
    }
}
