package com.zhiban.rebuild.data.crm

import org.junit.Assert.assertEquals
import org.junit.Test

class CrmOpportunityStageTest {
    @Test fun allWritersShareOneStageProbabilityPolicy() {
        assertEquals(10, CrmOpportunityStage.probabilityPercent(CrmOpportunityStage.LEAD))
        assertEquals(25, CrmOpportunityStage.probabilityPercent(CrmOpportunityStage.CONTACTED))
        assertEquals(45, CrmOpportunityStage.probabilityPercent(CrmOpportunityStage.QUALIFIED))
        assertEquals(65, CrmOpportunityStage.probabilityPercent(CrmOpportunityStage.PROPOSAL))
        assertEquals(80, CrmOpportunityStage.probabilityPercent(CrmOpportunityStage.NEGOTIATION))
        assertEquals(100, CrmOpportunityStage.probabilityPercent(CrmOpportunityStage.WON))
        assertEquals(0, CrmOpportunityStage.probabilityPercent(CrmOpportunityStage.LOST))
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownStageCannotSilentlyBecomeZeroProbability() {
        CrmOpportunityStage.probabilityPercent("UNKNOWN")
    }
}
