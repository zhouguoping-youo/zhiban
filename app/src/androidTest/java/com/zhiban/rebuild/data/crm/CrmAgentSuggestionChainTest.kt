package com.zhiban.rebuild.data.crm

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.agent.CrmAgentDataRepository
import com.zhiban.rebuild.data.contact.ContactEntity
import com.zhiban.rebuild.data.notification.NotificationCandidateEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * In-memory Room tests for the event-driven CRM suggestion chain (第五步): triggers, the
 * confidence gate, the undoable accept path, and the PENDING→EXPIRED lifecycle.
 */
@RunWith(AndroidJUnit4::class)
class CrmAgentSuggestionChainTest {
    private lateinit var db: AgentDatabase
    private lateinit var repo: CrmAgentDataRepository

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AgentDatabase::class.java)
            .addCallback(AgentDatabase.CALLBACK).allowMainThreadQueries().build()
        repo = CrmAgentDataRepository(db)
    }

    @After fun tearDown() = db.close()

    private fun contact(id: String, name: String) = ContactEntity(
        contactId = id,
        displayName = name,
        normalizedName = name,
        phone = null,
        email = null,
        wechatId = null,
        company = "甲公司",
        title = null,
        aliasesJson = "[]",
        tagsJson = "[]",
        note = null,
        avatarUri = null,
        source = "MANUAL",
        deletedAtEpochMs = null,
        createdAtEpochMs = 1,
        updatedAtEpochMs = 1,
    )

    private fun opportunity(id: String, contactId: String?, status: String = CrmRecordStatus.OPEN) = CrmOpportunityEntity(
        opportunityId = id,
        title = "商机$id",
        accountNameSnapshot = "客户$id",
        primaryContactId = contactId,
        sourceLeadId = null,
        stage = CrmOpportunityStage.QUALIFIED,
        status = status,
        valueMinor = null,
        currencyCode = "CNY",
        probabilityPercent = 45,
        expectedCloseAtEpochMs = null,
        productSummary = null,
        needSummary = null,
        lossReason = null,
        sourceType = "USER_CONFIRMED",
        createdAtEpochMs = 1,
        updatedAtEpochMs = 1,
    )

    private suspend fun insertContactAndOpenOpportunity(contactId: String, opportunityId: String) {
        db.contactDao().insert(contact(contactId, "联系人$contactId"))
        db.crmDao().insertOpportunity(opportunity(opportunityId, contactId))
    }

    // ---- 通话 → 跟进建议 ----

    @Test fun callFollowUpSuggestedWhenContactHasOpenOpportunity() = runBlocking {
        insertContactAndOpenOpportunity("c1", "o1")

        val created = repo.suggestCallFollowUpActivity("c1", "call-1", 600, nowEpochMs = 1_000L)

        assertTrue(created)
        val pending = db.crmDao().observePendingSuggestions(0.0).first()
        assertEquals(1, pending.size)
        val suggestion = pending.single()
        assertEquals(CrmSuggestionType.CALL_FOLLOW_UP, suggestion.suggestionType)
        assertEquals("o1", suggestion.opportunityId)
        assertEquals("c1", suggestion.contactId)
        assertTrue(suggestion.confidence >= 0.7)
        assertTrue(suggestion.evidenceRefsJson.contains("call-1"))
    }

    @Test fun noCallFollowUpWhenContactHasNoOpenOpportunity() = runBlocking {
        db.contactDao().insert(contact("c1", "联系人"))
        db.crmDao().insertOpportunity(opportunity("o1", "c1", status = CrmRecordStatus.WON))

        val created = repo.suggestCallFollowUpActivity("c1", "call-1", 600, nowEpochMs = 1_000L)

        assertTrue(!created)
        assertEquals(0, db.crmDao().observePendingSuggestions(0.0).first().size)
    }

    @Test fun callFollowUpDeduplicatedPerOpportunity() = runBlocking {
        insertContactAndOpenOpportunity("c1", "o1")

        assertTrue(repo.suggestCallFollowUpActivity("c1", "call-1", 600, nowEpochMs = 1_000L))
        assertTrue(!repo.suggestCallFollowUpActivity("c1", "call-2", 300, nowEpochMs = 2_000L))
        assertEquals(1, db.crmDao().observePendingSuggestions(0.0).first().size)
    }

    @Test fun processedCallEvidenceIsNeverSuggestedAgainButANewCallCanBeSuggested() = runBlocking {
        insertContactAndOpenOpportunity("c1", "o1")
        assertTrue(repo.suggestCallFollowUpActivity("c1", "call-1", 600, nowEpochMs = 1_000L))
        val first = db.crmDao().observePendingSuggestions(0.0).first().single()
        assertTrue(repo.setCrmSuggestionStatus(first.suggestionId, accepted = false))

        assertTrue(!repo.suggestCallFollowUpActivity("c1", "call-1", 600, nowEpochMs = 2_000L))
        assertTrue(repo.suggestCallFollowUpActivity("c1", "call-2", 300, nowEpochMs = 2_000L))
    }

    // ---- 通知 → 线索建议 ----

    @Test fun newLeadSuggestedForHighConfidenceMatchWithoutLead() = runBlocking {
        db.contactDao().insert(contact("c1", "高置信联系人"))
        insertCandidate("cand-1", "c1", 0.9)

        val created = repo.suggestNewLeadFromNotification("c1", "cand-1", nowEpochMs = 1_000L)

        assertTrue(created)
        val suggestion = db.crmDao().observePendingSuggestions(0.0).first().single()
        assertEquals(CrmSuggestionType.NEW_LEAD, suggestion.suggestionType)
        assertNull(suggestion.opportunityId)
        assertEquals("c1", suggestion.contactId)
        assertEquals(0.9, suggestion.confidence, 0.0001)
        assertEquals("cand-1", Json.parseToJsonElement(suggestion.evidenceRefsJson).jsonArray.single().jsonPrimitive.content)
    }

    @Test fun newLeadSuggestionRejectsMissingOrMismatchedEvidence() = runBlocking {
        db.contactDao().insert(contact("c1", "联系人一"))
        db.contactDao().insert(contact("c2", "联系人二"))
        insertCandidate("cand-mismatch", "c2", 0.9)

        assertTrue(!repo.suggestNewLeadFromNotification("c1", "missing", nowEpochMs = 1_000L))
        assertTrue(!repo.suggestNewLeadFromNotification("c1", "cand-mismatch", nowEpochMs = 1_000L))
        assertEquals(0, db.crmDao().observePendingSuggestions(0.0).first().size)
    }

    @Test fun newLeadSuggestionSerializesEvidenceIdAsJson() = runBlocking {
        db.contactDao().insert(contact("c1", "联系人"))
        val candidateId = "cand-\"quoted"
        insertCandidate(candidateId, "c1", 0.9)

        assertTrue(repo.suggestNewLeadFromNotification("c1", candidateId, nowEpochMs = 1_000L))

        val evidence = db.crmDao().observePendingSuggestions(0.0).first().single().evidenceRefsJson
        assertEquals(candidateId, Json.parseToJsonElement(evidence).jsonArray.single().jsonPrimitive.content)
    }

    @Test fun newLeadNotSuggestedBelowConfidenceThreshold() = runBlocking {
        db.contactDao().insert(contact("c1", "联系人"))
        insertCandidate("cand-1", "c1", 0.5)

        val created = repo.suggestNewLeadFromNotification("c1", "cand-1", nowEpochMs = 1_000L)

        assertTrue(!created)
        assertEquals(0, db.crmDao().observePendingSuggestions(0.0).first().size)
    }

    @Test fun newLeadSuggestionRejectsOutOfRangeConfidence() = runBlocking {
        db.contactDao().insert(contact("c1", "联系人"))
        insertCandidate("cand-overflow", "c1", 1.1)

        assertTrue(!repo.suggestNewLeadFromNotification("c1", "cand-overflow", nowEpochMs = 1_000L))
        assertEquals(0, db.crmDao().observePendingSuggestions(0.0).first().size)
    }

    @Test fun newLeadNotSuggestedWhenContactAlreadyHasLead() = runBlocking {
        db.contactDao().insert(contact("c1", "联系人"))
        db.crmDao().insertLead(existingLead("lead-1", "c1"))
        insertCandidate("cand-1", "c1", 0.9)

        val created = repo.suggestNewLeadFromNotification("c1", "cand-1", nowEpochMs = 1_000L)

        assertTrue(!created)
    }

    // ---- 置信度门槛（展示侧）----

    @Test fun pendingSuggestionsFlowHonoursConfidenceThreshold() = runBlocking {
        insertContactAndOpenOpportunity("c1", "o1")
        repo.suggestCallFollowUpActivity("c1", "call-1", 600, nowEpochMs = 1_000L)
        db.crmDao().upsertSuggestions(listOf(lowConfidenceSuggestion("sug-low", "o1", "c1", confidence = 0.5)))

        val all = db.crmDao().observePendingSuggestions(0.0).first()
        val shown = db.crmDao().observePendingSuggestions(0.7).first()

        assertEquals(2, all.size)
        assertEquals(1, shown.size)
        assertEquals(CrmSuggestionType.CALL_FOLLOW_UP, shown.single().suggestionType)
    }

    // ---- 接受 → 可撤销写入 ----

    @Test fun acceptCallFollowUpWritesUndoableActivity() = runBlocking {
        insertContactAndOpenOpportunity("c1", "o1")
        repo.suggestCallFollowUpActivity("c1", "call-1", 600, nowEpochMs = 1_000L)
        val suggestion = db.crmDao().observePendingSuggestions(0.0).first().single()

        val accepted = repo.acceptCallFollowUpSuggestion(suggestion.suggestionId, nowEpochMs = 2_000L)

        assertTrue(accepted)
        assertEquals(CrmSuggestionStatus.ACCEPTED, db.crmDao().findSuggestion(suggestion.suggestionId)?.status)
        val activities = db.crmDao().observeActivities("o1").first()
        assertEquals(1, activities.size)
        assertEquals("CALL", activities.single().activityType)
        val change = db.changeLogDao().findByIdempotencyKey("crm-suggestion-accept:${suggestion.suggestionId}")
        assertNotNull(change)
        assertEquals("AVAILABLE", change?.undoState)
        assertEquals("crm.suggestion.acceptActivity", change?.toolName)
        assertNotNull(db.changeLogDao().findAutoWriteReceipt(change!!.changeId))
    }

    @Test fun undoAcceptedCallFollowUpRestoresPendingAndDeletesActivity() = runBlocking {
        insertContactAndOpenOpportunity("c1", "o1")
        repo.suggestCallFollowUpActivity("c1", "call-1", 600, nowEpochMs = 1_000L)
        val suggestion = db.crmDao().observePendingSuggestions(0.0).first().single()
        repo.acceptCallFollowUpSuggestion(suggestion.suggestionId, nowEpochMs = 2_000L)
        val change = db.changeLogDao().findByIdempotencyKey("crm-suggestion-accept:${suggestion.suggestionId}")!!

        val undone = com.zhiban.rebuild.runtime.governance.ChangeUndoCoordinator(db)
            .undoVisibleInTransaction(change.changeId, nowEpochMs = 3_000L)

        assertNotNull(undone)
        assertEquals(CrmSuggestionStatus.PENDING, db.crmDao().findSuggestion(suggestion.suggestionId)?.status)
        assertEquals(0, db.crmDao().observeActivities("o1").first().size)
    }

    @Test fun acceptNewLeadWritesUndoableLead() = runBlocking {
        db.contactDao().insert(contact("c1", "新线索联系人"))
        insertCandidate("cand-1", "c1", 0.9)
        repo.suggestNewLeadFromNotification("c1", "cand-1", nowEpochMs = 1_000L)
        val suggestion = db.crmDao().observePendingSuggestions(0.0).first().single()

        val accepted = repo.acceptNewLeadSuggestion(suggestion.suggestionId, nowEpochMs = 2_000L)

        assertTrue(accepted)
        assertEquals(CrmSuggestionStatus.ACCEPTED, db.crmDao().findSuggestion(suggestion.suggestionId)?.status)
        val lead = db.crmDao().findLeadByContact("c1")
        assertNotNull(lead)
        assertEquals(CrmLeadStatus.NEW, lead?.status)
        assertTrue(lead?.userConfirmed == true)
        val change = db.changeLogDao().findByIdempotencyKey("crm-suggestion-accept:${suggestion.suggestionId}")
        assertNotNull(change)
        assertEquals("AVAILABLE", change?.undoState)
        assertEquals("crm.suggestion.acceptLead", change?.toolName)
    }

    @Test fun undoAcceptedNewLeadRestoresPendingAndDeletesLead() = runBlocking {
        db.contactDao().insert(contact("c1", "新线索联系人"))
        insertCandidate("cand-1", "c1", 0.9)
        repo.suggestNewLeadFromNotification("c1", "cand-1", nowEpochMs = 1_000L)
        val suggestion = db.crmDao().observePendingSuggestions(0.0).first().single()
        repo.acceptNewLeadSuggestion(suggestion.suggestionId, nowEpochMs = 2_000L)
        val change = db.changeLogDao().findByIdempotencyKey("crm-suggestion-accept:${suggestion.suggestionId}")!!

        val undone = com.zhiban.rebuild.runtime.governance.ChangeUndoCoordinator(db)
            .undoVisibleInTransaction(change.changeId, nowEpochMs = 3_000L)

        assertNotNull(undone)
        assertEquals(CrmSuggestionStatus.PENDING, db.crmDao().findSuggestion(suggestion.suggestionId)?.status)
        assertNull(db.crmDao().findLeadByContact("c1"))
    }

    // ---- 生命周期：PENDING → EXPIRED ----

    @Test fun stalePendingSuggestionsExpireAfterSevenDays() = runBlocking {
        insertContactAndOpenOpportunity("c1", "o1")
        repo.suggestCallFollowUpActivity("c1", "call-1", 600, nowEpochMs = 1_000L)
        val staleSuggestion = db.crmDao().observePendingSuggestions(0.0).first().single()
        // A fresh suggestion created well inside the 7-day window relative to `now`.
        val freshCreatedAt = 6L * 24 * 60 * 60 * 1_000 // 6 days in
        db.crmDao().upsertSuggestions(listOf(lowConfidenceSuggestion("sug-fresh", "o1", "c1", confidence = 0.9, createdAt = freshCreatedAt)))
        val now = 1_000L + 8L * 24 * 60 * 60 * 1_000 // 8 days after the stale one, past the 7-day TTL

        val expired = repo.expireStaleSuggestions(now)

        assertEquals(1, expired)
        assertEquals(CrmSuggestionStatus.EXPIRED, db.crmDao().findSuggestion(staleSuggestion.suggestionId)?.status)
        assertEquals(CrmSuggestionStatus.PENDING, db.crmDao().findSuggestion("sug-fresh")?.status)
    }

    @Test fun expiredSuggestionCannotBeResurrectedByAStaleUiDecision() = runBlocking {
        insertContactAndOpenOpportunity("c1", "o1")
        repo.suggestCallFollowUpActivity("c1", "call-1", 600, nowEpochMs = 1_000L)
        val suggestion = db.crmDao().observePendingSuggestions(0.0).first().single()
        val now = 1_000L + 8L * 24 * 60 * 60 * 1_000
        assertEquals(1, repo.expireStaleSuggestions(now))

        assertTrue(!repo.setCrmSuggestionStatus(suggestion.suggestionId, accepted = true))
        assertEquals(CrmSuggestionStatus.EXPIRED, db.crmDao().findSuggestion(suggestion.suggestionId)?.status)
    }

    private fun existingLead(id: String, contactId: String) = CrmLeadEntity(
        leadId = id,
        contactId = contactId,
        displayNameSnapshot = "已有线索",
        companyNameSnapshot = null,
        status = CrmLeadStatus.NEW,
        sourceType = "USER_CONFIRMED",
        sourceRef = null,
        fitSummary = null,
        confidence = 1.0,
        userConfirmed = true,
        createdAtEpochMs = 1,
        updatedAtEpochMs = 1,
    )

    private suspend fun insertCandidate(candidateId: String, contactId: String, confidence: Double) {
        db.notificationCandidateDao().upsert(
            NotificationCandidateEntity(
                candidateId = candidateId,
                sourceKey = "source-$candidateId",
                packageName = "com.tencent.mm",
                appLabel = "微信",
                title = "联系人",
                body = "收到",
                postedAtEpochMs = 1,
                suggestedContactId = contactId,
                suggestedContactConfidence = confidence,
            ),
        )
    }

    private fun lowConfidenceSuggestion(id: String, opportunityId: String, contactId: String, confidence: Double, createdAt: Long = 1L) =
        CrmAgentSuggestionEntity(
            suggestionId = id,
            opportunityId = opportunityId,
            contactId = contactId,
            suggestionType = CrmSuggestionType.NEW_LEAD,
            title = "建议",
            summary = "摘要",
            rationale = "依据",
            evidenceRefsJson = "[]",
            confidence = confidence,
            proposedActionJson = null,
            status = CrmSuggestionStatus.PENDING,
            createdAtEpochMs = createdAt,
            updatedAtEpochMs = createdAt,
        )
}
