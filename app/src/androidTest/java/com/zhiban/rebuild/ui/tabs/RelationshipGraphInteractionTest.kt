package com.zhiban.rebuild.ui.tabs

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.zhiban.rebuild.ui.theme.ZhiBanTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class RelationshipGraphInteractionTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun personNodeHasNamedTouchTargetAndOpensContact() {
        var clickCount = 0
        compose.setContent {
            ZhiBanTheme {
                GraphPersonNode(
                    person = RelationshipPersonUi("person-1", "李应啸", isOwner = false),
                    onClick = { clickCount++ },
                )
            }
        }

        compose.onNodeWithContentDescription("查看李应啸").performClick()
        compose.runOnIdle { assertEquals(1, clickCount) }
    }
}
