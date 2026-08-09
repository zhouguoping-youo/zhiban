package com.zhiban.rebuild.ui.components

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import com.zhiban.rebuild.navigation.Calendar
import com.zhiban.rebuild.navigation.Home
import com.zhiban.rebuild.navigation.Profile
import com.zhiban.rebuild.navigation.Relation
import com.zhiban.rebuild.navigation.Skill
import com.zhiban.rebuild.ui.theme.ZhiBanTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ZhiBanBottomBarTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun everyIconOnlyDestinationIsNamedAndClickable() {
        val selectedRoutes = mutableListOf<Any>()
        compose.setContent {
            ZhiBanTheme {
                ZhiBanBottomBar(
                    currentDestination = null,
                    onTabSelected = selectedRoutes::add,
                )
            }
        }

        listOf("日历", "关系", "问问", "能力", "我的").forEach { label ->
            compose.onNodeWithContentDescription(label).assertHasClickAction().performClick()
        }

        assertEquals(
            listOf(Calendar(), Relation, Home, Skill, Profile).map { it::class },
            selectedRoutes.map { it::class },
        )
    }

    @Test
    fun longPressRevealsDestinationName() {
        compose.setContent {
            ZhiBanTheme {
                ZhiBanBottomBar(currentDestination = null, onTabSelected = {})
            }
        }

        compose.onNodeWithContentDescription("能力").performTouchInput { longClick() }

        compose.onNodeWithText("能力").assertIsDisplayed()
    }
}
