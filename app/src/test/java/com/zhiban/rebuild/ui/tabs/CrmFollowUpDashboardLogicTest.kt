package com.zhiban.rebuild.ui.tabs

import com.zhiban.rebuild.data.crm.CrmActionStatus
import com.zhiban.rebuild.data.crm.CrmNextActionEntity
import com.zhiban.rebuild.data.crm.CrmOpportunityEntity
import com.zhiban.rebuild.data.crm.CrmOpportunityStage
import com.zhiban.rebuild.data.crm.CrmRecordStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CrmFollowUpDashboardLogicTest {
    private val dayMs = 86_400_000L
    private val todayStart = 1_000_000L
    private val tomorrowStart = todayStart + dayMs

    private fun actionUi(id: String, dueAt: Long?) = CrmActionUi(
        CrmNextActionEntity(
            actionId = id,
            opportunityId = "opp-1",
            contactId = null,
            actionType = "CALL",
            title = "待办$id",
            dueAtEpochMs = dueAt,
            status = CrmActionStatus.PENDING,
            priority = 0,
            rationale = null,
            sourceType = "USER_CONFIRMED",
            scheduleId = null,
            createdAtEpochMs = 1,
            updatedAtEpochMs = 1,
        ),
        opportunityTitle = "商机",
        accountName = "客户",
        contactName = "联系人",
    )

    private fun opportunityUi(id: String, stage: String, status: String, valueMinor: Long?) = CrmOpportunityUi(
        CrmOpportunityEntity(
            opportunityId = id,
            title = "商机$id",
            accountNameSnapshot = "客户$id",
            primaryContactId = null,
            sourceLeadId = null,
            stage = stage,
            status = status,
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

    @Test fun followUpGroupsSplitByLocalDayBoundaries() {
        val groups = buildCrmFollowUpGroups(
            listOf(
                actionUi("yesterday", todayStart - 1),
                actionUi("midnight", todayStart),
                actionUi("evening", tomorrowStart - 1),
                actionUi("nextWeek", tomorrowStart + dayMs),
                actionUi("nodate", null),
            ),
            todayStart,
            tomorrowStart,
        )

        assertEquals(listOf("yesterday"), groups.overdue.map { it.entity.actionId })
        assertEquals(listOf("midnight", "evening"), groups.dueToday.map { it.entity.actionId })
        assertEquals(listOf("nextWeek"), groups.upcoming.map { it.entity.actionId })
        assertEquals(listOf("nodate"), groups.unscheduled.map { it.entity.actionId })
        assertEquals(3, groups.needsAttentionCount)
    }

    @Test fun followUpGroupsTreatEmptyAndUndatedAsNoReminder() {
        val groups = buildCrmFollowUpGroups(listOf(actionUi("nodate", null)), todayStart, tomorrowStart)
        assertTrue(groups.overdue.isEmpty())
        assertTrue(groups.dueToday.isEmpty())
        assertEquals(0, groups.needsAttentionCount)
    }

    @Test fun dashboardSumsOpenValueAndCountsTerminalStages() {
        val followUps = buildCrmFollowUpGroups(
            listOf(actionUi("o", todayStart - 1), actionUi("t", todayStart + 1)),
            todayStart,
            tomorrowStart,
        )
        val dashboard = buildCrmDashboardUi(
            opportunities = listOf(
                opportunityUi("open1", CrmOpportunityStage.PROPOSAL, CrmRecordStatus.OPEN, 500_00L),
                opportunityUi("open2", CrmOpportunityStage.LEAD, CrmRecordStatus.OPEN, null),
                opportunityUi("won", CrmOpportunityStage.WON, CrmRecordStatus.WON, 900_00L),
                opportunityUi("lost", CrmOpportunityStage.LOST, CrmRecordStatus.LOST, 100_00L),
            ),
            followUps = followUps,
            newLeadsCount = 0,
            activitiesCount = 0,
        )

        // Only OPEN amounts count toward pipeline value; terminal deals are excluded.
        assertEquals(500_00L, dashboard.openOpportunityValueMinor)
        assertEquals(1, dashboard.wonOpportunityCount)
        assertEquals(1, dashboard.lostOpportunityCount)
        assertEquals(1, dashboard.overdueActionCount)
        assertEquals(1, dashboard.dueTodayActionCount)
    }

    @Test fun dashboardPassesThroughWindowCounts() {
        val dashboard = buildCrmDashboardUi(
            opportunities = emptyList(),
            followUps = CrmFollowUpGroups(),
            newLeadsCount = 3,
            activitiesCount = 5,
        )

        assertEquals(3, dashboard.newLeadsCount)
        assertEquals(5, dashboard.activitiesCount)
    }

    @Test fun dashboardIsEmptyOnlyWhenNothingToReport() {
        assertTrue(CrmDashboardUi().isEmpty)
        val empty = buildCrmDashboardUi(
            opportunities = emptyList(),
            followUps = CrmFollowUpGroups(),
            newLeadsCount = 0,
            activitiesCount = 0,
        )
        assertTrue(empty.isEmpty)
    }
}
