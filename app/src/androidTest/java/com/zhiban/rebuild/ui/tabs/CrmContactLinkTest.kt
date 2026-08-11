package com.zhiban.rebuild.ui.tabs

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.agent.CrmAgentDataRepository
import com.zhiban.rebuild.data.contact.ContactEntity
import com.zhiban.rebuild.data.crm.CrmLeadStatus
import com.zhiban.rebuild.data.crm.CrmOpportunityEntity
import com.zhiban.rebuild.data.crm.CrmOpportunityStage
import com.zhiban.rebuild.data.crm.CrmRecordStatus
import com.zhiban.rebuild.data.crm.CrmSuggestionStatus
import com.zhiban.rebuild.data.crm.CrmSuggestionType
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
class CrmContactLinkTest {
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

    private suspend fun insertContact(id: String, name: String = "张总", company: String? = "甲公司") {
        database.contactDao().insert(
            ContactEntity(id, name, name, null, null, null, company, null, "[]", "[]", null, null, "USER", null, 1, 1),
        )
    }

    private suspend fun insertOpportunity(id: String, contactId: String, stage: String = CrmOpportunityStage.PROPOSAL, status: String = CrmRecordStatus.OPEN) {
        database.crmDao().insertOpportunity(
            CrmOpportunityEntity(
                opportunityId = id,
                title = "商机$id",
                accountNameSnapshot = "甲公司",
                primaryContactId = contactId,
                sourceLeadId = null,
                stage = stage,
                status = status,
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
        )
    }

    @Test fun observeOpportunitiesByContactReturnsOnlyThatContact() = runBlocking {
        insertContact("c1")
        insertContact("c2", name = "李总")
        insertOpportunity("o1", "c1")
        insertOpportunity("o2", "c2")

        val forC1 = repository.observeCrmOpportunitiesByContact("c1").first()
        assertEquals(listOf("o1"), forC1.map { it.opportunityId })
    }

    @Test fun mergedContactSeesSourceCrmRecordsAndUndoRestoresOriginalScope() = runBlocking {
        insertContact("canonical", "主联系人")
        insertContact("source", "待合并联系人")
        insertOpportunity("source-opportunity", "source")
        database.contactIdentityDao().upsertMergeLink(
            com.zhiban.rebuild.data.contact.ContactMergeLinkEntity(
                sourceContactId = "source",
                canonicalContactId = "canonical",
                reason = "测试合并",
                userConfirmed = true,
                createdAtEpochMs = 10,
                undoneAtEpochMs = null,
            ),
        )

        assertEquals("source-opportunity", repository.observeCrmOpportunitiesByContact("canonical").first().single().opportunityId)
        assertEquals("source-opportunity", repository.findOpenOpportunityForContact("canonical")?.opportunityId)

        assertTrue(database.contactIdentityDao().undoConfirmedMerge("source", 20) == 1)
        assertTrue(repository.observeCrmOpportunitiesByContact("canonical").first().isEmpty())
        assertEquals("source-opportunity", repository.observeCrmOpportunitiesByContact("source").first().single().opportunityId)
    }

    @Test fun suggestCallFollowUpCreatesPendingSuggestionForOpenOpportunity() = runBlocking {
        insertContact("c1")
        insertOpportunity("o1", "c1")

        val created = repository.suggestCallFollowUpActivity("c1", "call-1", durationSeconds = 480, nowEpochMs = 10)

        assertTrue(created)
        val pending = database.crmDao().observePendingSuggestions(0.0).first()
        assertEquals(1, pending.size)
        assertEquals(CrmSuggestionType.CALL_FOLLOW_UP, pending.first().suggestionType)
        assertEquals("o1", pending.first().opportunityId)
        assertEquals(CrmSuggestionStatus.PENDING, pending.first().status)
    }

    @Test fun suggestCallFollowUpIsSkippedWithoutOpenOpportunity() = runBlocking {
        insertContact("c1")
        insertOpportunity("o1", "c1", status = CrmRecordStatus.WON) // no OPEN opportunity

        assertTrue(!repository.suggestCallFollowUpActivity("c1", "call-1", 60, 10))
        assertEquals(0, database.crmDao().observePendingSuggestions(0.0).first().size)
    }

    @Test fun suggestCallFollowUpDoesNotDuplicatePendingSuggestion() = runBlocking {
        insertContact("c1")
        insertOpportunity("o1", "c1")

        assertTrue(repository.suggestCallFollowUpActivity("c1", "call-1", 60, 10))
        assertTrue(!repository.suggestCallFollowUpActivity("c1", "call-2", 60, 11))
        assertEquals(1, database.crmDao().observePendingSuggestions(0.0).first().size)
    }

    @Test fun acceptCallFollowUpWritesCallActivityAndMarksAccepted() = runBlocking {
        insertContact("c1")
        insertOpportunity("o1", "c1")
        repository.suggestCallFollowUpActivity("c1", "call-1", 480, 10)
        val suggestionId = database.crmDao().observePendingSuggestions(0.0).first().first().suggestionId

        assertTrue(repository.acceptCallFollowUpSuggestion(suggestionId, nowEpochMs = 20))

        val activities = database.crmDao().observeActivities("o1").first()
        assertEquals(1, activities.size)
        assertEquals("CALL", activities.first().activityType)
        assertEquals("c1", activities.first().contactId)
        assertEquals(CrmSuggestionStatus.ACCEPTED, database.crmDao().findSuggestion(suggestionId)!!.status)
    }

    @Test fun createLeadForContactIfAbsentCreatesNewLeadOnlyOnce() = runBlocking {
        insertContact("c1")

        val leadId = repository.createLeadForContactIfAbsent("c1", "cand-1", 10)
        assertNotNull(leadId)
        val lead = database.crmDao().findLead(leadId!!)!!
        assertEquals(CrmLeadStatus.NEW, lead.status)
        assertEquals("c1", lead.contactId)
        assertEquals("张总", lead.displayNameSnapshot)

        // Second confirmation for the same contact does not create a duplicate lead.
        assertNull(repository.createLeadForContactIfAbsent("c1", "cand-2", 11))
    }

    @Test fun contactDetailShowsOpenOpportunitiesSection() {
        val contact = ContactEntity(
            "c1", "张总", "张总", null, null, null, "甲公司", null, "[]", "[]", null, null, "USER", null, 1, 1,
        )
        val openOpp = CrmOpportunityEntity(
            opportunityId = "o1",
            title = "甲公司年度合作",
            accountNameSnapshot = "甲公司",
            primaryContactId = "c1",
            sourceLeadId = null,
            stage = CrmOpportunityStage.PROPOSAL,
            status = CrmRecordStatus.OPEN,
            valueMinor = 500_00L,
            currencyCode = "CNY",
            probabilityPercent = 65,
            expectedCloseAtEpochMs = null,
            productSummary = null,
            needSummary = null,
            lossReason = null,
            sourceType = "USER_CONFIRMED",
            createdAtEpochMs = 1,
            updatedAtEpochMs = 1,
        )
        compose.setContent {
            ZhiBanTheme {
                ContactDetailDialog(
                    contact = contact,
                    showMarkAsOwner = false,
                    facts = emptyList(),
                    aliases = emptyList(),
                    platformIdentities = emptyList(),
                    mergedSources = emptyList(),
                    relatedEdges = emptyList(),
                    relatedEvents = emptyList(),
                    recentCalls = emptyList(),
                    crmOpportunities = listOf(openOpp),
                    enrichmentSuggestions = emptyList(),
                    contactNames = emptyMap(),
                    onDismiss = {},
                    onEdit = {},
                    onMarkAsOwner = {},
                    onDelete = {},
                    onAddFact = {},
                    onAddEvent = {},
                    onAddIdentity = {},
                    onInspectEvent = {},
                    onDeleteFact = {},
                    onDeleteAlias = {},
                    onDeletePlatformIdentity = {},
                    onUndoMerge = {},
                    onConfirmEnrichment = {},
                    onRejectEnrichment = {},
                    onSaveToPhone = {},
                    onCall = {},
                    onMessage = {},
                )
            }
        }

        compose.onNodeWithText("进行中的商机").assertIsDisplayed()
        compose.onNodeWithTag("contact-crm-opp-o1").assertIsDisplayed()
        compose.onNodeWithText("甲公司年度合作").assertIsDisplayed()
    }

    @Test fun contactDetailUsesCompactHierarchyAndKeepsActionsReachable() {
        var edited = false
        val contact = ContactEntity(
            "c1", "丁波", "丁波", "13800000000", null, "wx-dingbo", "甲公司", "售前", "[]", "[]", null,
            null, "USER", null, 1, 1,
        )
        compose.setContent {
            ZhiBanTheme {
                ContactDetailDialog(
                    contact = contact,
                    showMarkAsOwner = false,
                    facts = emptyList(),
                    aliases = emptyList(),
                    platformIdentities = emptyList(),
                    mergedSources = emptyList(),
                    relatedEdges = emptyList(),
                    relatedEvents = emptyList(),
                    recentCalls = emptyList(),
                    crmOpportunities = emptyList(),
                    enrichmentSuggestions = emptyList(),
                    contactNames = emptyMap(),
                    onDismiss = {},
                    onEdit = { edited = true },
                    onMarkAsOwner = {},
                    onDelete = {},
                    onAddFact = {},
                    onAddEvent = {},
                    onAddIdentity = {},
                    onInspectEvent = {},
                    onDeleteFact = {},
                    onDeleteAlias = {},
                    onDeletePlatformIdentity = {},
                    onUndoMerge = {},
                    onConfirmEnrichment = {},
                    onRejectEnrichment = {},
                    onSaveToPhone = {},
                    onCall = {},
                    onMessage = {},
                )
            }
        }

        compose.onNodeWithText("编辑").performClick()
        assertTrue(edited)
        compose.onNodeWithText("电话").assertIsDisplayed()
        compose.onNodeWithText("短信").assertIsDisplayed()
        compose.onNodeWithText("资料").assertIsDisplayed()
        compose.onNodeWithText("身份与称呼").assertDoesNotExist()
        compose.onNodeWithText("你确认的信息").assertDoesNotExist()
        compose.onNodeWithText("暂无已关联的通话记录").assertDoesNotExist()

        compose.onNodeWithTag("contact-detail-content")
            .performScrollToNode(hasTestTag("contact-detail-sync"))
        compose.onNodeWithTag("contact-detail-sync").assertIsDisplayed()
        compose.onNodeWithText("同步到手机通讯录").assertIsDisplayed()
        compose.onNodeWithText("写入前可预览").assertIsDisplayed()
    }
}
