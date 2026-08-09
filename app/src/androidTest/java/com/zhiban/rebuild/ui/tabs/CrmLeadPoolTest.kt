package com.zhiban.rebuild.ui.tabs

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.agent.CrmAgentDataRepository
import com.zhiban.rebuild.data.agent.CrmLeadConversionInput
import com.zhiban.rebuild.data.crm.CrmLeadEntity
import com.zhiban.rebuild.data.crm.CrmLeadStatus
import com.zhiban.rebuild.data.crm.CrmOpportunityStage
import com.zhiban.rebuild.ui.theme.ZhiBanTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CrmLeadPoolTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var database: AgentDatabase
    private lateinit var repository: CrmAgentDataRepository

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AgentDatabase::class.java)
            .addCallback(AgentDatabase.CALLBACK).allowMainThreadQueries().build()
        repository = CrmAgentDataRepository(database)
    }

    @After fun tearDown() = database.close()

    private suspend fun insertLeadWithContact(id: String, status: String, name: String = "王建国", company: String? = "星河科技") {
        database.contactDao().insert(
            com.zhiban.rebuild.data.contact.ContactEntity(
                "contact-$id", name, name, null, null, null, company, null, "[]", "[]", null, null, "USER", null, 1, 1,
            ),
        )
        database.crmDao().insertLead(lead(id, status, name, company))
    }

    private fun lead(id: String, status: String, name: String = "王建国", company: String? = "星河科技") = CrmLeadEntity(
        leadId = id,
        contactId = "contact-$id",
        displayNameSnapshot = name,
        companyNameSnapshot = company,
        status = status,
        sourceType = "AGENT_AUTO",
        sourceRef = "msg-$id",
        fitSummary = "连续询价",
        confidence = 0.9,
        userConfirmed = false,
        createdAtEpochMs = 1,
        updatedAtEpochMs = 1,
    )

    // ---- conversion logic (CANDIDATE/NEW → CONVERTED + opportunity + stage history + activity) ----

    @Test fun convertLeadCreatesOpportunityHistoryAndActivity() = runBlocking {
        insertLeadWithContact("l1", CrmLeadStatus.QUALIFIED)

        val opportunityId = repository.convertLeadToOpportunity(
            "l1",
            CrmLeadConversionInput("星河科技 合作", "星河科技", 1_000_000L, 1_800_000_000_000L),
        )

        assertNotNull(opportunityId)
        assertEquals(CrmLeadStatus.CONVERTED, database.crmDao().findLead("l1")!!.status)
        val opportunity = database.crmDao().findOpportunity(opportunityId!!)!!
        assertEquals(CrmOpportunityStage.LEAD, opportunity.stage)
        assertEquals("l1", opportunity.sourceLeadId)
        assertEquals("contact-l1", opportunity.primaryContactId)
        assertEquals(1, database.crmDao().observeStageHistory(opportunityId).first().size)
        val activities = database.crmDao().observeActivities(opportunityId).first()
        assertEquals(1, activities.size)
        assertEquals("CONVERSION", activities.first().activityType)
    }

    @Test fun convertLeadIsRejectedForCandidateAndConvertedLead() = runBlocking {
        insertLeadWithContact("cand", CrmLeadStatus.CANDIDATE)
        insertLeadWithContact("done", CrmLeadStatus.CONVERTED)
        val input = CrmLeadConversionInput("标题", "客户", null, null)

        assertNull(repository.convertLeadToOpportunity("cand", input))
        assertNull(repository.convertLeadToOpportunity("done", input))
        assertEquals(CrmLeadStatus.CANDIDATE, database.crmDao().findLead("cand")!!.status)
        assertEquals(0, database.crmDao().countOpportunities())
    }

    @Test fun qualifyMovesNewLeadToQualifiedAndDisqualifyMarksIt() = runBlocking {
        insertLeadWithContact("q1", CrmLeadStatus.NEW)
        insertLeadWithContact("d1", CrmLeadStatus.CONTACTED)

        assertTrue(repository.qualifyCrmLead("q1"))
        assertEquals(CrmLeadStatus.QUALIFIED, database.crmDao().findLead("q1")!!.status)
        assertTrue(repository.disqualifyCrmLead("d1"))
        assertEquals(CrmLeadStatus.DISQUALIFIED, database.crmDao().findLead("d1")!!.status)
    }

    @Test fun qualifyIsRejectedForAlreadyQualifiedLead() = runBlocking {
        insertLeadWithContact("q2", CrmLeadStatus.QUALIFIED)
        // qualify from QUALIFIED is a no-op guard (already terminal for this transition).
        assertTrue(!repository.qualifyCrmLead("q2"))
        assertEquals(CrmLeadStatus.QUALIFIED, database.crmDao().findLead("q2")!!.status)
    }

    // ---- Compose cards ----

    @Test fun leadPoolCardExposesQualifyConvertAndDisqualifyActions() {
        var qualified = 0
        var converted = 0
        var disqualified = 0
        val lead = lead("pool-1", CrmLeadStatus.NEW)
        compose.setContent {
            ZhiBanTheme {
                CrmLeadPoolCard(lead, onQualify = { qualified++ }, onConvert = { converted++ }, onDisqualify = { disqualified++ })
            }
        }

        compose.onNodeWithTag("crm-lead-pool-1").assertIsDisplayed()
        compose.onNodeWithTag("crm-lead-qualify-pool-1").performClick()
        compose.onNodeWithTag("crm-lead-convert-pool-1").performClick()
        compose.onNodeWithTag("crm-lead-disqualify-pool-1").performClick()

        compose.runOnIdle {
            assertEquals(1, qualified)
            assertEquals(1, converted)
            assertEquals(1, disqualified)
        }
    }

    @Test fun qualifiedLeadHidesQualifyAction() {
        val lead = lead("pool-q", CrmLeadStatus.QUALIFIED)
        compose.setContent {
            ZhiBanTheme {
                CrmLeadPoolCard(lead, onQualify = {}, onConvert = {}, onDisqualify = {})
            }
        }
        compose.onNodeWithTag("crm-lead-convert-pool-q").assertIsDisplayed()
        compose.onNodeWithText("标记需求").assertDoesNotExist()
    }

    @Test fun convertDialogBuildsInputFromFields() {
        var captured: CrmLeadConversionInput? = null
        val lead = lead("pool-c", CrmLeadStatus.QUALIFIED, name = "张总", company = "甲公司")
        compose.setContent {
            ZhiBanTheme {
                CrmConvertLeadDialog(lead = lead, onDismiss = {}, onConfirm = { captured = it })
            }
        }

        compose.onNodeWithTag("crm-convert-confirm").performClick()

        compose.runOnIdle {
            assertNotNull(captured)
            assertEquals("甲公司 合作", captured!!.title)
            assertEquals("甲公司", captured!!.accountName)
            assertNull(captured!!.valueMinor)
        }
    }
}
