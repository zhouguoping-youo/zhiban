package com.zhiban.rebuild.data.contact

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.agent.ContactAgentDataRepository
import com.zhiban.rebuild.data.crm.CrmActionStatus
import com.zhiban.rebuild.data.crm.CrmActivityEntity
import com.zhiban.rebuild.data.crm.CrmAgentSuggestionEntity
import com.zhiban.rebuild.data.crm.CrmLeadEntity
import com.zhiban.rebuild.data.crm.CrmNextActionEntity
import com.zhiban.rebuild.data.crm.CrmOpportunityEntity
import com.zhiban.rebuild.data.crm.CrmOpportunityStage
import com.zhiban.rebuild.data.crm.CrmOpportunityStakeholderEntity
import com.zhiban.rebuild.data.crm.CrmRecordStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContactSoftDeleteCrmTest {
    private lateinit var db: AgentDatabase
    private lateinit var repository: ContactAgentDataRepository

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AgentDatabase::class.java)
            .addCallback(AgentDatabase.CALLBACK).allowMainThreadQueries().build()
        repository = ContactAgentDataRepository(db)
    }

    @After fun tearDown() = db.close()

    @Test fun softDeleteDetachesEveryCrmContactReferenceAndPreservesBusinessHistory() = runBlocking {
        db.contactDao().insert(contact())
        db.crmDao().insertLead(lead())
        db.crmDao().insertOpportunity(opportunity())
        db.crmDao().upsertStakeholders(listOf(stakeholder()))
        db.crmDao().insertActivity(activity())
        db.crmDao().insertAction(action())
        db.crmDao().upsertSuggestions(listOf(suggestion()))

        assertTrue(repository.deleteUserContact(CONTACT_ID))

        assertNull(db.crmDao().findLead(LEAD_ID)!!.contactId)
        assertNull(db.crmDao().findOpportunity(OPPORTUNITY_ID)!!.primaryContactId)
        assertTrue(db.crmDao().observeStakeholders(OPPORTUNITY_ID).first().isEmpty())
        assertNull(db.crmDao().findActivity(ACTIVITY_ID)!!.contactId)
        assertNull(db.crmDao().findAction(ACTION_ID)!!.contactId)
        assertNull(db.crmDao().findSuggestion(SUGGESTION_ID)!!.contactId)
        assertEquals("客户快照", db.crmDao().findLead(LEAD_ID)!!.displayNameSnapshot)
        assertEquals("历史商机", db.crmDao().findOpportunity(OPPORTUNITY_ID)!!.title)
    }

    private fun contact() = ContactEntity(
        CONTACT_ID, "待删除客户", "待删除客户", null, null, null, null, null,
        "[]", "[]", null, null, "USER", null, 1, 1,
    )

    private fun lead() = CrmLeadEntity(
        LEAD_ID, CONTACT_ID, "客户快照", null, "QUALIFIED", "USER_CONFIRMED", null,
        null, 1.0, true, 1, 1,
    )

    private fun opportunity() = CrmOpportunityEntity(
        OPPORTUNITY_ID, "历史商机", "客户公司", CONTACT_ID, LEAD_ID, CrmOpportunityStage.QUALIFIED,
        CrmRecordStatus.OPEN, null, "CNY", 45, null, null, null, null, "USER_CONFIRMED", 1, 1,
    )

    private fun stakeholder() = CrmOpportunityStakeholderEntity(
        OPPORTUNITY_ID, CONTACT_ID, "DECISION_MAKER", "HIGH", true, 1,
    )

    private fun activity() = CrmActivityEntity(
        ACTIVITY_ID, OPPORTUNITY_ID, CONTACT_ID, "CALL", "沟通", "历史沟通", 1,
        "USER_CONFIRMED", null, null, true, 1,
    )

    private fun action() = CrmNextActionEntity(
        ACTION_ID, OPPORTUNITY_ID, CONTACT_ID, "FOLLOW_UP", "继续跟进", null,
        CrmActionStatus.PENDING, 1, null, "USER_CONFIRMED", null, 1, 1,
    )

    private fun suggestion() = CrmAgentSuggestionEntity(
        SUGGESTION_ID, OPPORTUNITY_ID, CONTACT_ID, "CALL_FOLLOW_UP", "建议", "摘要", "理由",
        "[]", 0.8, null, "PENDING", 1, 1,
    )

    private companion object {
        const val CONTACT_ID = "contact-delete"
        const val LEAD_ID = "lead-delete"
        const val OPPORTUNITY_ID = "opportunity-delete"
        const val ACTIVITY_ID = "activity-delete"
        const val ACTION_ID = "action-delete"
        const val SUGGESTION_ID = "suggestion-delete"
    }
}
