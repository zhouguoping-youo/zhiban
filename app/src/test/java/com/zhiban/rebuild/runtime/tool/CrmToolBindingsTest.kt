package com.zhiban.rebuild.runtime.tool

import com.zhiban.rebuild.data.contact.ContactDao
import com.zhiban.rebuild.data.contact.ContactEntity
import com.zhiban.rebuild.data.crm.CrmActivityEntity
import com.zhiban.rebuild.data.crm.CrmAgentSuggestionEntity
import com.zhiban.rebuild.data.crm.CrmDao
import com.zhiban.rebuild.data.crm.CrmNextActionEntity
import com.zhiban.rebuild.data.crm.CrmOpportunityEntity
import com.zhiban.rebuild.data.crm.CrmOpportunityStakeholderEntity
import com.zhiban.rebuild.data.crm.CrmStageHistoryEntity
import com.zhiban.rebuild.provider.ProviderFailure
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrmToolBindingsTest {
    private val opportunity = CrmOpportunityEntity(
        opportunityId = "opp-1",
        title = "私有化部署项目",
        accountNameSnapshot = "甲公司",
        primaryContactId = "contact-1",
        sourceLeadId = "lead-1",
        stage = "PROPOSAL",
        status = "OPEN",
        valueMinor = 68_000_000,
        currencyCode = "CNY",
        probabilityPercent = 60,
        expectedCloseAtEpochMs = 2_000,
        productSummary = "企业版",
        needSummary = "私有化部署",
        lossReason = null,
        sourceType = "USER_CONFIRMED",
        createdAtEpochMs = 1,
        updatedAtEpochMs = 2,
    )

    @Test fun listReturnsOnlyMatchingRealOpportunityFields() = runTest {
        val crm = mockk<CrmDao>()
        every { crm.observeOpportunities() } returns flowOf(listOf(opportunity))
        val spec = RuntimeToolCatalog.production().requireRegistered("crm.opportunity.list")

        val result = CrmOpportunityListToolBinding(spec, crm).executeReadOnly(
            RuntimeToolCallRequest("call", spec.name, """{"query":"甲公司","stage":"PROPOSAL"}"""),
            context(),
        )

        assertTrue(result.safeResultJson.contains("私有化部署项目"))
        assertTrue(result.safeResultJson.contains("\"valueMinor\":68000000"))
        assertFalse(result.safeResultJson.contains("needSummary"))
    }

    @Test fun listRejectsUnknownArguments() = runTest {
        val crm = mockk<CrmDao>()
        val spec = RuntimeToolCatalog.production().requireRegistered("crm.opportunity.list")
        val failure = runCatching {
            CrmOpportunityListToolBinding(spec, crm).executeReadOnly(
                RuntimeToolCallRequest("call", spec.name, """{"sql":"DROP TABLE"}"""),
                context(),
            )
        }.exceptionOrNull()

        assertTrue(failure is ProviderFailure)
    }

    @Test fun detailConnectsStakeholdersActionsEvidenceAndStageHistory() = runTest {
        val crm = mockk<CrmDao>()
        val contacts = mockk<ContactDao>()
        coEvery { crm.findOpportunity("opp-1") } returns opportunity
        every { crm.observeStakeholders("opp-1") } returns flowOf(
            listOf(
                CrmOpportunityStakeholderEntity("opp-1", "contact-1", "DECISION_MAKER", "HIGH", true, 1),
            ),
        )
        every { crm.observeActivities("opp-1") } returns flowOf(
            listOf(
                CrmActivityEntity("activity-1", "opp-1", "contact-1", "MEETING", "需求会", "已确认预算", 10, "USER", null, "会议纪要", true, 10),
            ),
        )
        every { crm.observeActions("opp-1") } returns flowOf(
            listOf(
                CrmNextActionEntity("action-1", "opp-1", "contact-1", "FOLLOW_UP", "发送方案", 20, "PENDING", 3, "客户已确认需求", "AGENT", "schedule-1", 10, 10),
            ),
        )
        every { crm.observeSuggestions("opp-1") } returns flowOf(
            listOf(
                CrmAgentSuggestionEntity("suggestion-1", "opp-1", "contact-1", "MESSAGE", "准备报价说明", "解释价格构成", "客户询问预算", "[]", .9, null, "PENDING", 10, 10),
            ),
        )
        every { crm.observeStageHistory("opp-1") } returns flowOf(
            listOf(
                CrmStageHistoryEntity("history-1", "opp-1", "QUALIFIED", "PROPOSAL", "需求确认", "USER", true, 10),
            ),
        )
        coEvery { contacts.findById("contact-1") } returns ContactEntity(
            "contact-1", "王建国", "王建国", null, null, null, "甲公司", "总监", "[]", "[]", null, null, "USER", null, 1, 1,
        )
        val spec = RuntimeToolCatalog.production().requireRegistered("crm.opportunity.get")

        val result = CrmOpportunityDetailToolBinding(spec, crm, contacts).executeReadOnly(
            RuntimeToolCallRequest("call", spec.name, """{"opportunityId":"opp-1"}"""),
            context(),
        )

        assertTrue(result.safeResultJson.contains("王建国"))
        assertTrue(result.safeResultJson.contains("schedule-1"))
        assertTrue(result.safeResultJson.contains("会议纪要"))
        assertTrue(result.safeResultJson.contains("QUALIFIED"))
    }

    private fun context() = RuntimeToolRouteContext("run", "session", "attempt", "owner", 1, 1, 1)
}
