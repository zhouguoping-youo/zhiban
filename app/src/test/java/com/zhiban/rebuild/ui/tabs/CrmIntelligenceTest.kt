package com.zhiban.rebuild.ui.tabs

import com.zhiban.rebuild.data.crm.CrmActionStatus
import com.zhiban.rebuild.data.crm.CrmNextActionEntity
import com.zhiban.rebuild.data.crm.CrmOpportunityEntity
import com.zhiban.rebuild.data.crm.CrmOpportunityStage
import com.zhiban.rebuild.data.crm.CrmRecordStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CrmIntelligenceTest {
    private fun opportunity(id: String = "opp-1", stage: String = CrmOpportunityStage.QUALIFIED, needSummary: String? = "需要提升跟进效率") = CrmOpportunityUi(
        entity = CrmOpportunityEntity(
            opportunityId = id,
            title = "续约机会",
            accountNameSnapshot = "示例公司",
            primaryContactId = null,
            sourceLeadId = null,
            stage = stage,
            status = CrmRecordStatus.OPEN,
            valueMinor = 100_00,
            currencyCode = "CNY",
            probabilityPercent = CrmOpportunityStage.probabilityPercent(stage),
            expectedCloseAtEpochMs = null,
            productSummary = null,
            needSummary = needSummary,
            lossReason = null,
            sourceType = "USER_CONFIRMED",
            createdAtEpochMs = 1,
            updatedAtEpochMs = 2,
        ),
        contactName = "联系人",
    )

    private fun action(dueAtEpochMs: Long?) = CrmActionUi(
        entity = CrmNextActionEntity(
            actionId = "action-1",
            opportunityId = "opp-1",
            contactId = null,
            actionType = "CALL",
            title = "确认预算",
            dueAtEpochMs = dueAtEpochMs,
            status = CrmActionStatus.PENDING,
            priority = 1,
            rationale = "上次沟通约定",
            sourceType = "USER_CONFIRMED",
            scheduleId = null,
            createdAtEpochMs = 1,
            updatedAtEpochMs = 1,
        ),
        opportunityTitle = "续约机会",
        accountName = "示例公司",
        contactName = "联系人",
    )

    @Test
    fun workbenchPrioritizesOverdueBeforeTodayAndMissingAction() {
        val overdue = action(50)
        val today = action(150).copy(entity = action(150).entity.copy(actionId = "action-2"))
        val state = CrmWorkbenchUiState(
            opportunities = listOf(opportunity(), opportunity("opp-2")),
            actions = listOf(overdue, today),
            followUps = CrmFollowUpGroups(overdue = listOf(overdue), dueToday = listOf(today)),
        )

        val priorities = buildCrmPriorities(state)

        assertEquals(CrmPriorityKind.OVERDUE, priorities[0].kind)
        assertEquals(CrmPriorityKind.DUE_TODAY, priorities[1].kind)
        assertEquals(CrmPriorityKind.MISSING_NEXT_ACTION, priorities[2].kind)
    }

    @Test
    fun detailGuidanceUsesEvidenceInsteadOfInventedScore() {
        val guidance = buildCrmOpportunityGuidance(
            CrmOpportunityDetailUiState(
                opportunity = opportunity(),
                actions = listOf(action(50)),
                isLoading = false,
            ),
            nowEpochMs = 100,
        )

        assertEquals("先完成逾期跟进", guidance?.title)
        assertTrue(guidance?.evidence.orEmpty().contains("仍未完成"))
    }

    @Test
    fun detailGuidanceCallsOutMissingNextActionAndStakeholder() {
        val noAction = buildCrmOpportunityGuidance(
            CrmOpportunityDetailUiState(opportunity = opportunity(), isLoading = false),
            nowEpochMs = 100,
        )
        val noStakeholder = buildCrmOpportunityGuidance(
            CrmOpportunityDetailUiState(
                opportunity = opportunity(),
                actions = listOf(action(200)),
                isLoading = false,
            ),
            nowEpochMs = 100,
        )

        assertEquals("确定下一步动作", noAction?.title)
        assertEquals("确认关键关系人", noStakeholder?.title)
    }

    @Test
    fun opportunityStatusExplainsOverdueAndMissingAction() {
        assertTrue(crmOpportunityStatusLine(opportunity(), listOf(action(50)), 100).startsWith("已逾期"))
        assertEquals("尚未安排下一步", crmOpportunityStatusLine(opportunity(), emptyList(), 100))
    }
}
