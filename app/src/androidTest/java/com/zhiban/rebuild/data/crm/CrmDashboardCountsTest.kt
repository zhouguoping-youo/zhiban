package com.zhiban.rebuild.data.crm

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhiban.rebuild.data.agent.AgentDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CrmDashboardCountsTest {
    private lateinit var db: AgentDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AgentDatabase::class.java)
            .addCallback(AgentDatabase.CALLBACK).allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    private fun lead(id: String, status: String, createdAt: Long) = CrmLeadEntity(
        leadId = id,
        contactId = null,
        displayNameSnapshot = "线索$id",
        companyNameSnapshot = null,
        status = status,
        sourceType = "USER_CONFIRMED",
        sourceRef = null,
        fitSummary = null,
        confidence = 1.0,
        userConfirmed = true,
        createdAtEpochMs = createdAt,
        updatedAtEpochMs = createdAt,
    )

    @Test fun countsOnlyFormalLeadsAndActivitiesInsideWindow() = runBlocking {
        val dao = db.crmDao()
        val since = 1_000L
        dao.insertLead(lead("recent", CrmLeadStatus.NEW, since + 1))
        dao.insertLead(lead("old", CrmLeadStatus.NEW, since - 1))
        dao.insertLead(lead("candidate", CrmLeadStatus.CANDIDATE, since + 1))
        dao.insertOpportunity(
            CrmOpportunityEntity(
                opportunityId = "opp-1",
                title = "商机",
                accountNameSnapshot = "客户",
                primaryContactId = null,
                sourceLeadId = null,
                stage = CrmOpportunityStage.PROPOSAL,
                status = CrmRecordStatus.OPEN,
                valueMinor = null,
                currencyCode = "CNY",
                probabilityPercent = 65,
                expectedCloseAtEpochMs = null,
                productSummary = null,
                needSummary = null,
                lossReason = null,
                sourceType = "USER_CONFIRMED",
                createdAtEpochMs = 1,
                updatedAtEpochMs = 1,
            ),
        )
        dao.insertActivity(
            CrmActivityEntity(
                activityId = "act-in",
                opportunityId = "opp-1",
                contactId = null,
                activityType = "CALL",
                title = "窗内活动",
                summary = "",
                occurredAtEpochMs = since + 1,
                sourceType = "USER_CONFIRMED",
                sourceRef = null,
                evidenceSummary = null,
                userConfirmed = true,
                createdAtEpochMs = since + 1,
            ),
        )
        dao.insertActivity(
            CrmActivityEntity(
                activityId = "act-out",
                opportunityId = "opp-1",
                contactId = null,
                activityType = "CALL",
                title = "窗外活动",
                summary = "",
                occurredAtEpochMs = since - 1,
                sourceType = "USER_CONFIRMED",
                sourceRef = null,
                evidenceSummary = null,
                userConfirmed = true,
                createdAtEpochMs = since - 1,
            ),
        )

        val counts = dao.observeDashboardActivityCounts(since).first()

        // Candidates never count as new leads; only rows at/after the window start are included.
        assertEquals(1, counts.newLeadCount)
        assertEquals(1, counts.activityCount)
    }

    @Test fun emitsZeroAndReEmitsOnInsert() = runBlocking {
        val dao = db.crmDao()
        val since = 1_000L

        val empty = dao.observeDashboardActivityCounts(since).first()
        assertEquals(0, empty.newLeadCount)
        assertEquals(0, empty.activityCount)

        // The query is reactive: a later write inside the window is reflected without re-querying.
        dao.insertLead(lead("late", CrmLeadStatus.QUALIFIED, since + 5))
        val after = dao.observeDashboardActivityCounts(since).first()
        assertEquals(1, after.newLeadCount)
    }
}
