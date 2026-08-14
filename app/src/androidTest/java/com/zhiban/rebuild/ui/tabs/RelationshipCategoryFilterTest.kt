package com.zhiban.rebuild.ui.tabs

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.zhiban.rebuild.ui.theme.ZhiBanTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class RelationshipCategoryFilterTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun categoriesStayCollapsedUntilRequestedThenCloseAfterSelection() {
        var selected by mutableStateOf("全部")
        compose.setContent {
            ZhiBanTheme {
                RelationshipCategoryFilter(
                    selected = selected,
                    options = listOf("全部", "家人", "朋友同学", "工作"),
                    onSelected = { selected = it },
                )
            }
        }

        compose.onNodeWithText("家人").assertDoesNotExist()
        compose.onNodeWithTag("relation-category-filter").performClick()
        compose.onNodeWithText("家人").assertIsDisplayed().performClick()
        compose.onNodeWithText("朋友同学").assertDoesNotExist()
        compose.onNodeWithTag("relation-category-filter").assertTextContains("家人")
    }

    @Test
    fun collapsedCategoryControlKeepsAFullWidthFortyEightDpTouchTarget() {
        compose.setContent {
            ZhiBanTheme {
                RelationshipCategoryFilter(
                    selected = "全部",
                    options = listOf("全部", "家人"),
                    onSelected = {},
                )
            }
        }

        val bounds = compose.onNodeWithTag("relation-category-filter").getUnclippedBoundsInRoot()
        assertTrue("filter touch target must be at least 48dp", bounds.bottom - bounds.top >= 48.dp)
        assertTrue("collapsed filter should use the available row width", bounds.right - bounds.left > 240.dp)
    }
}
