package com.zhiban.rebuild.ui.tabs

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhiban.rebuild.data.crm.CrmOpportunityEntity
import com.zhiban.rebuild.data.crm.CrmOpportunityStage
import com.zhiban.rebuild.data.crm.CrmRecordStatus
import com.zhiban.rebuild.ui.theme.ZhiBanTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CrmOpportunityBoardUiTest {
    @get:Rule
    val compose = createComposeRule()

    private fun opportunity(id: String, stage: String) = CrmOpportunityUi(
        CrmOpportunityEntity(
            opportunityId = id,
            title = "商机$id",
            accountNameSnapshot = "客户$id",
            primaryContactId = null,
            sourceLeadId = null,
            stage = stage,
            status = CrmRecordStatus.OPEN,
            valueMinor = 100_00L,
            currencyCode = "CNY",
            probabilityPercent = CrmOpportunityStage.probabilityPercent(stage),
            expectedCloseAtEpochMs = null,
            productSummary = null,
            needSummary = null,
            lossReason = null,
            sourceType = "USER_CONFIRMED",
            createdAtEpochMs = 1,
            updatedAtEpochMs = 1,
        ),
        contactName = "张总",
    )

    @Test fun activeStageCardOffersAdvanceToNextStage() {
        var advanced = 0
        compose.setContent {
            ZhiBanTheme {
                CrmBoardCard(
                    opportunity = opportunity("b1", CrmOpportunityStage.LEAD),
                    isTerminal = false,
                    nextStageLabel = crmStageLabel(CrmOpportunityStage.CONTACTED),
                    onOpen = {},
                    onAdvance = { advanced++ },
                )
            }
        }

        compose.onNodeWithTag("crm-board-card-b1").assertIsDisplayed()
        compose.onNodeWithTag("crm-board-advance-b1").performClick()

        compose.runOnIdle { assertEquals(1, advanced) }
    }

    @Test fun terminalStageCardHidesAdvanceAction() {
        compose.setContent {
            ZhiBanTheme {
                CrmBoardCard(
                    opportunity = opportunity("b2", CrmOpportunityStage.WON),
                    isTerminal = true,
                    nextStageLabel = null,
                    onOpen = {},
                    onAdvance = {},
                )
            }
        }

        compose.onNodeWithTag("crm-board-card-b2").assertIsDisplayed()
        compose.onNodeWithText("详情").assertIsDisplayed()
        // No advance affordance for a closed (WON/LOST) opportunity.
        compose.onAllNodesWithText("→", substring = true).assertCountEquals(0)
    }

    @Test fun boardColumnHeaderShowsCountAndSummedValue() {
        val column = buildCrmBoardColumns(
            listOf(
                opportunity("c1", CrmOpportunityStage.PROPOSAL),
                opportunity("c2", CrmOpportunityStage.PROPOSAL),
            ),
        ).first { it.stage == CrmOpportunityStage.PROPOSAL }
        compose.setContent {
            ZhiBanTheme {
                CrmBoardColumnView(column = column, onOpenOpportunity = {}, onAdvance = {})
            }
        }

        compose.onNodeWithTag("crm-board-column-PROPOSAL").assertIsDisplayed()
        compose.onNodeWithText("2 条 · ¥200", substring = false).assertIsDisplayed()
    }

    @Test fun everyStageIsReachableWithoutHorizontalScrolling() {
        val columns = buildCrmBoardColumns(
            listOf(opportunity("lead", CrmOpportunityStage.LEAD), opportunity("lost", CrmOpportunityStage.LOST)),
        )
        compose.setContent {
            ZhiBanTheme {
                CrmOpportunityBoardContent(columns = columns, onOpenOpportunity = {}, onAdvance = { _, _ -> })
            }
        }

        compose.onNodeWithTag("crm-board-column-LOST").performScrollTo().assertIsDisplayed()
    }
}
