package com.zhiban.rebuild.ui.tabs

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.zhiban.rebuild.ui.theme.ZhiBanTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CalendarEmptyStateTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun emptyDayOffersOneWorkingAddAction() {
        var addCount = 0
        compose.setContent {
            ZhiBanTheme {
                EmptyDay(onAdd = { addCount++ })
            }
        }

        compose.onNodeWithText("这一天还没有安排").assertIsDisplayed()
        compose.onNodeWithText("添加日程").performClick()
        compose.runOnIdle { assertEquals(1, addCount) }
    }
}
