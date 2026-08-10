package com.zhiban.rebuild.ui.tabs

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.zhiban.rebuild.ui.theme.ZhiBanTheme
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class CalendarBackHandlerTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun systemBackCollapsesTheExpandedMonthBeforeLeavingThePage() {
        val expanded = mutableStateOf(true)
        compose.setContent {
            ZhiBanTheme {
                CalendarMonthBackHandler(expanded.value) { expanded.value = false }
            }
        }

        compose.runOnUiThread {
            compose.activity.onBackPressedDispatcher.onBackPressed()
        }
        compose.runOnIdle { assertFalse(expanded.value) }
    }
}
