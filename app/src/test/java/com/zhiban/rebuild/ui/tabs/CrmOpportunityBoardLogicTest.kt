package com.zhiban.rebuild.ui.tabs

import com.zhiban.rebuild.data.crm.CrmOpportunityEntity
import com.zhiban.rebuild.data.crm.CrmOpportunityStage
import com.zhiban.rebuild.data.crm.CrmRecordStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrmOpportunityBoardLogicTest {
    private fun opportunity(id: String, stage: String, valueMinor: Long?) = CrmOpportunityUi(
        CrmOpportunityEntity(
            opportunityId = id,
            title = "商机$id",
            accountNameSnapshot = "客户$id",
            primaryContactId = null,
            sourceLeadId = null,
            stage = stage,
            status = CrmRecordStatus.OPEN,
            valueMinor = valueMinor,
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
        contactName = "联系人$id",
    )

    @Test fun boardGroupsOpportunitiesIntoSevenStageColumnsInOrder() {
        val opportunities = listOf(
            opportunity("a", CrmOpportunityStage.LEAD, 100_00L),
            opportunity("b", CrmOpportunityStage.LEAD, 200_00L),
            opportunity("c", CrmOpportunityStage.PROPOSAL, 500_00L),
            opportunity("d", CrmOpportunityStage.WON, 900_00L),
        )

        val columns = buildCrmBoardColumns(opportunities)

        assertEquals(CrmOpportunityStage.allStages, columns.map { it.stage })
        assertEquals(7, columns.size)
        val lead = columns.first { it.stage == CrmOpportunityStage.LEAD }
        assertEquals(2, lead.count)
        assertEquals(300_00L, lead.totalValueMinor)
        val proposal = columns.first { it.stage == CrmOpportunityStage.PROPOSAL }
        assertEquals(1, proposal.count)
        assertEquals(500_00L, proposal.totalValueMinor)
        // Empty stages still get a (zero) column so the board always renders all seven.
        assertEquals(0, columns.first { it.stage == CrmOpportunityStage.NEGOTIATION }.count)
    }

    @Test fun boardColumnValueSumsOnlyPresentAmounts() {
        val columns = buildCrmBoardColumns(
            listOf(
                opportunity("x", CrmOpportunityStage.CONTACTED, null),
                opportunity("y", CrmOpportunityStage.CONTACTED, 150_00L),
            ),
        )
        val contacted = columns.first { it.stage == CrmOpportunityStage.CONTACTED }
        assertEquals(2, contacted.count)
        assertEquals(150_00L, contacted.totalValueMinor)
    }

    @Test fun nextBoardStageAdvancesAndStopsAtWon() {
        assertEquals(CrmOpportunityStage.CONTACTED, nextCrmBoardStage(CrmOpportunityStage.LEAD))
        assertEquals(CrmOpportunityStage.NEGOTIATION, nextCrmBoardStage(CrmOpportunityStage.PROPOSAL))
        assertEquals(CrmOpportunityStage.WON, nextCrmBoardStage(CrmOpportunityStage.NEGOTIATION))
    }

    @Test fun nextBoardStageIsNullForTerminalStages() {
        assertNull(nextCrmBoardStage(CrmOpportunityStage.WON))
        assertNull(nextCrmBoardStage(CrmOpportunityStage.LOST))
    }

    @Test fun terminalColumnsAreMarkedSoCardsCannotAdvance() {
        val columns = buildCrmBoardColumns(
            listOf(
                opportunity("w", CrmOpportunityStage.WON, 1L),
                opportunity("l", CrmOpportunityStage.LOST, 1L),
                opportunity("o", CrmOpportunityStage.LEAD, 1L),
            ),
        )
        assertTrue(columns.first { it.stage == CrmOpportunityStage.WON }.isTerminal)
        assertTrue(columns.first { it.stage == CrmOpportunityStage.LOST }.isTerminal)
        assertTrue(!columns.first { it.stage == CrmOpportunityStage.LEAD }.isTerminal)
    }
}
