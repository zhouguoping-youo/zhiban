package com.zhiban.rebuild.ui.tabs

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhiban.rebuild.data.agent.RelationshipEventParticipantInput
import com.zhiban.rebuild.data.contact.ContactEntity
import com.zhiban.rebuild.ui.theme.ZhiBanTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RelationshipEventEditorTest {
    @get:Rule
    val compose = createComposeRule()

    @Test fun introductionRequiresSearchingAndSelectingCanonicalContact() {
        val subject = contact("subject", "丁波")
        val introducer = contact("introducer", "王芳", company = "甲公司")
        val other = contact("other", "王强", company = "乙公司")
        var savedTitle = ""
        var savedParticipants = emptyList<RelationshipEventParticipantInput>()

        compose.setContent {
            ZhiBanTheme {
                RelationshipEventEditorDialog(
                    contacts = listOf(subject, introducer, other),
                    subject = subject,
                    existing = null,
                    onDismiss = {},
                    onSave = { _, title, _, participants, result ->
                        savedTitle = title
                        savedParticipants = participants
                        result(null)
                    },
                )
            }
        }

        compose.onNodeWithText("王芳").assertDoesNotExist()
        compose.onNodeWithText("确认保存").performScrollTo().performClick()
        compose.onNodeWithText("介绍认识需要选择介绍人").assertIsDisplayed()

        compose.onNode(
            hasSetTextAction() and hasAnyAncestor(hasTestTag("relationship-participant-search")),
        ).performScrollTo().performTextInput("王芳")
        compose.onNodeWithTag("relationship-participant-introducer").performClick()
        compose.onNodeWithTag("relationship-participant-selected-introducer").assertIsDisplayed()
        compose.onNodeWithText("确认保存").performScrollTo().performClick()

        assertEquals("王芳介绍我认识丁波", savedTitle)
        assertTrue(
            savedParticipants.any {
                it.contactId == "introducer" && it.participantRole == "INTRODUCER"
            },
        )
    }

    private fun contact(id: String, name: String, company: String? = null) = ContactEntity(
        id,
        name,
        name,
        null,
        null,
        null,
        company,
        null,
        "[]",
        "[]",
        null,
        null,
        "USER",
        null,
        1,
        1,
    )
}
