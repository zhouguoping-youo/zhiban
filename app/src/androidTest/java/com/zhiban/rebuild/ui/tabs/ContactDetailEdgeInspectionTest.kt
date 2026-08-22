package com.zhiban.rebuild.ui.tabs

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.zhiban.rebuild.data.contact.ContactEntity
import com.zhiban.rebuild.data.contact.RelationshipEdgeEntity
import com.zhiban.rebuild.data.contact.RelationshipPersonIds
import com.zhiban.rebuild.ui.theme.ZhiBanTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ContactDetailEdgeInspectionTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun relationshipRowIsClickableAndReportsItsEdge() {
        val edge = RelationshipEdgeEntity(
            "self-c1",
            RelationshipPersonIds.SELF,
            "c1",
            "CUSTOMER",
            "测试依据",
            "[]",
            1.0,
            true,
            null,
            "ACTIVE",
            1,
            1,
        )
        var inspected: RelationshipEdgeEntity? = null
        compose.setContent {
            ZhiBanTheme {
                ContactDetailDialog(
                    contact = contact,
                    showMarkAsOwner = false,
                    facts = emptyList(),
                    aliases = emptyList(),
                    platformIdentities = emptyList(),
                    mergedSources = emptyList(),
                    relatedEdges = listOf(edge),
                    relatedEvents = emptyList(),
                    crmOpportunities = emptyList(),
                    enrichmentSuggestions = emptyList(),
                    contactNames = mapOf(RelationshipPersonIds.SELF to "我"),
                    onDismiss = {},
                    onEdit = {},
                    onMarkAsOwner = {},
                    onDelete = {},
                    onAddFact = {},
                    onAddRelationship = {},
                    onAddEvent = {},
                    onAddIdentity = {},
                    onInspectEvent = {},
                    onInspectEdge = { inspected = it },
                    onDeleteFact = {},
                    onDeleteAlias = {},
                    onDeletePlatformIdentity = {},
                    onUndoMerge = {},
                    onConfirmEnrichment = {},
                    onRejectEnrichment = {},
                    onSaveToPhone = {},
                    onCall = {},
                    onAsk = {},
                )
            }
        }

        compose.onNodeWithText("客户").assertHasClickAction().performClick()
        compose.runOnIdle { assertEquals(edge, inspected) }
    }

    private val contact = ContactEntity(
        "c1",
        "丁波",
        "丁波",
        null,
        null,
        null,
        null,
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
}
