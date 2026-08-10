package com.zhiban.rebuild.ui.tabs

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.zhiban.rebuild.data.contact.ContactEntity
import com.zhiban.rebuild.data.contact.RelationshipEdgeEntity
import com.zhiban.rebuild.runtime.personalization.UserProfile
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

    @Test
    fun ownerCenteredGraphRendersWhenGeneratedEdgesOnlyConnectContacts() {
        compose.setContent {
            ZhiBanTheme {
                RelationshipGraphState(
                    owner = UserProfile(name = "周国平"),
                    contacts = listOf(contact("a", "联系人甲"), contact("b", "联系人乙")),
                    edges = listOf(edge("a", "b")),
                    events = emptyList(),
                    canAddRelationship = true,
                    activeFilter = null,
                    onAdd = {},
                    onInspect = {},
                    onInspectEvent = {},
                    onDelete = {},
                )
            }
        }

        compose.onNodeWithText("我的关系图").assertExists()
        compose.onNodeWithText("关系网络").assertExists()
        compose.onNodeWithContentDescription("重置关系图视图").assertExists()
    }

    private fun contact(id: String, name: String) = ContactEntity(
        id,
        name,
        name,
        null,
        null,
        null,
        "知伴科技有限公司",
        null,
        "[]",
        "[]",
        null,
        null,
        "TEST",
        null,
        1,
        1,
    )

    private fun edge(from: String, to: String) = RelationshipEdgeEntity(
        "$from-$to",
        from,
        to,
        "COLLEAGUE",
        "同公司",
        "[]",
        0.9,
        false,
        null,
        INFERRED_COMPANY_RELATIONSHIP_STATUS,
        1,
        1,
    )
}
