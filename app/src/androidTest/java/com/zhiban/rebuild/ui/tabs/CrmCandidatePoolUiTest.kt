package com.zhiban.rebuild.ui.tabs

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhiban.rebuild.data.crm.CrmLeadEntity
import com.zhiban.rebuild.data.crm.CrmLeadStatus
import com.zhiban.rebuild.ui.theme.ZhiBanTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CrmCandidatePoolUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun candidateCardExposesSeparatePromoteAndIgnoreActions() {
        var promoted = 0
        var ignored = 0
        val lead = CrmLeadEntity(
            "lead-ui", "contact-ui", "王建国", "星河科技", CrmLeadStatus.CANDIDATE,
            "AGENT_AUTO", "message-ui", "连续询价", 0.99, false, 1, 1,
        )
        compose.setContent {
            ZhiBanTheme {
                CrmCandidateLeadCard(lead, onPromote = { promoted++ }, onIgnore = { ignored++ })
            }
        }

        compose.onNodeWithTag("crm-candidate-lead-ui").assertIsDisplayed()
        compose.onNodeWithTag("crm-candidate-promote-lead-ui").performClick()
        compose.onNodeWithTag("crm-candidate-ignore-lead-ui").performClick()

        compose.runOnIdle {
            assertEquals(1, promoted)
            assertEquals(1, ignored)
        }
    }

    @Test
    fun promotedLeadIsVisibleAsFormalAndIncludedInLeadOverview() {
        val lead = CrmLeadEntity(
            "lead-formal", "contact-ui", "王建国", "星河科技", CrmLeadStatus.QUALIFIED,
            "USER_CONFIRMED", "message-ui", "预算和需求已明确", 0.99, true, 1, 2,
        )
        compose.setContent {
            ZhiBanTheme {
                androidx.compose.foundation.layout.Column {
                    CrmFormalLeadCard(lead)
                    CrmStageOverview(emptyList(), formalLeadCount = 1, onStageClick = {})
                }
            }
        }

        compose.onNodeWithTag("crm-formal-lead-lead-formal").assertIsDisplayed()
        compose.onNodeWithTag("crm-stage-LEAD-count-1").assertIsDisplayed()
    }

    @Test
    fun emptyWorkbenchOffersOneWorkingNextStep() {
        var createCount = 0
        compose.setContent {
            ZhiBanTheme {
                CrmEmptyWorkbench(onCreateOpportunity = { createCount++ })
            }
        }

        compose.onNodeWithTag("crm-empty-workbench").assertIsDisplayed()
        compose.onNodeWithTag("crm-empty-create").assertIsDisplayed().performClick()

        compose.runOnIdle { assertEquals(1, createCount) }
    }

    @Test
    fun primaryPriorityCanOpenAndAskAgentWithoutExtraDialog() {
        var openCount = 0
        var prepareCount = 0
        compose.setContent {
            ZhiBanTheme {
                CrmPriorityCard(
                    priority = CrmPriorityUi(
                        kind = CrmPriorityKind.OVERDUE,
                        title = "确认预算",
                        context = "联系人 · 续约机会",
                        reason = "已逾期",
                        opportunityId = "opp-1",
                    ),
                    primary = true,
                    onOpen = { openCount++ },
                    onPrepare = { prepareCount++ },
                )
            }
        }

        compose.onNodeWithTag("crm-priority-overdue").assertIsDisplayed().performClick()
        compose.onNodeWithTag("crm-priority-prepare").performClick()
        compose.runOnIdle {
            assertEquals(1, openCount)
            assertEquals(1, prepareCount)
        }
    }
}
