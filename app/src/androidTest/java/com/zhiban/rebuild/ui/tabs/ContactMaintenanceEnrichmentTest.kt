package com.zhiban.rebuild.ui.tabs

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhiban.rebuild.data.contact.ContactEnrichmentCandidateEntity
import com.zhiban.rebuild.data.contact.ContactEntity
import com.zhiban.rebuild.ui.theme.ZhiBanTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContactMaintenanceEnrichmentTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun pendingSuggestionsAreVisibleAndExposeConfirmAndRejectActions() {
        val confirmed = mutableListOf<String>()
        val rejected = mutableListOf<String>()
        compose.setContent {
            ZhiBanTheme {
                ContactEnrichmentReviewSheet(
                    candidates = listOf(candidate("confirm-me"), candidate("reject-me")),
                    contacts = listOf(contact()),
                    error = null,
                    actions = ContactEnrichmentReviewActions(
                        onDismiss = {},
                        onConfirm = { confirmed += it.candidateId },
                        onReject = { rejected += it.candidateId },
                    ),
                )
            }
        }

        compose.onNodeWithText("2 条建议").assertExists()
        compose.onAllNodesWithText("审核联系人").assertCountEquals(2)
        compose.onAllNodesWithText("确认")[0].performClick()
        compose.onAllNodesWithText("拒绝")[1].performClick()

        compose.runOnIdle {
            assertEquals(listOf("confirm-me"), confirmed)
            assertEquals(listOf("reject-me"), rejected)
        }
    }

    private fun contact() = ContactEntity(
        "contact-1", "审核联系人", "审核联系人", null, null, null, null, null,
        "[]", "[]", null, null, "USER", null, 1, 1,
    )

    private fun candidate(id: String) = ContactEnrichmentCandidateEntity(
        candidateId = id,
        contactId = "contact-1",
        providerId = "test",
        fieldKind = "ORGANIZATION",
        proposedValueJson = """{"company":"测试公司"}""",
        sourceRef = "测试证据",
        confidence = 0.9,
        status = "PENDING",
        observedAtEpochMs = 1,
        expiresAtEpochMs = null,
        createdAtEpochMs = 1,
        updatedAtEpochMs = 1,
    )
}
