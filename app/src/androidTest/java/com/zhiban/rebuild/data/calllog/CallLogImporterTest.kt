package com.zhiban.rebuild.data.calllog

import android.content.Context
import android.provider.CallLog
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.agent.CrmAgentDataRepository
import com.zhiban.rebuild.data.contact.ContactEntity
import com.zhiban.rebuild.data.contact.ContactMergeLinkEntity
import com.zhiban.rebuild.data.contact.ContactMethodEntity
import com.zhiban.rebuild.data.crm.CrmOpportunityEntity
import com.zhiban.rebuild.data.crm.CrmOpportunityStage
import com.zhiban.rebuild.data.crm.CrmRecordStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CallLogImporterTest {
    private lateinit var database: AgentDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AgentDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun importsIdempotentlyAndLinksCountryCodeVariantThroughCanonicalPhone() = runTest {
        database.contactDao().insert(contact("contact-1"))
        database.contactKnowledgeDao().upsertMethods(listOf(method("contact-1", "13800138000")))
        val row = SystemCallLogRow(
            providerRowId = 42,
            number = "+86 138 0013 8000",
            numberPresentation = CallLog.Calls.PRESENTATION_ALLOWED,
            systemType = CallLog.Calls.INCOMING_TYPE,
            startedAtEpochMs = 1_000,
            durationSeconds = 60,
            lastModifiedEpochMs = 2_000,
            phoneAccountId = "1",
            phoneAccountComponentName = "phone",
        )

        CallLogImporter(database).import(listOf(row), 3_000)
        CallLogImporter(database).import(listOf(row.copy(durationSeconds = 61)), 4_000)

        val imported = database.callLogDao().findBySourceRow(CallLogImporter.SOURCE_ANDROID, 42)!!
        assertEquals("contact-1", imported.linkedContactId)
        assertEquals("13800138000", imported.normalizedNumber)
        assertEquals(61, imported.durationSeconds)

        database.contactDao().insert(contact("contact-2"))
        database.contactKnowledgeDao().upsertMethods(listOf(method("contact-2", "13800138000")))
        CallLogImporter(database).import(listOf(row.copy(durationSeconds = 62)), 5_000)
        val nowAmbiguous = database.callLogDao().findBySourceRow(CallLogImporter.SOURCE_ANDROID, 42)!!
        assertEquals("AMBIGUOUS", nowAmbiguous.linkState)
        assertEquals(null, nowAmbiguous.linkedContactId)
    }

    @Test
    fun privateOrAmbiguousNumberIsNeverAutoLinked() = runTest {
        database.contactDao().insert(contact("contact-1"))
        database.contactDao().insert(contact("contact-2"))
        database.contactKnowledgeDao().upsertMethods(
            listOf(
                method("contact-1", "13800138000"),
                method("contact-2", "13800138000"),
            ),
        )
        val importer = CallLogImporter(database)
        importer.import(listOf(row(1, "13800138000", CallLog.Calls.PRESENTATION_ALLOWED)), 3_000)
        importer.import(listOf(row(2, "13800138000", CallLog.Calls.PRESENTATION_RESTRICTED)), 3_000)

        assertEquals("AMBIGUOUS", database.callLogDao().findBySourceRow(CallLogImporter.SOURCE_ANDROID, 1)!!.linkState)
        assertEquals(null, database.callLogDao().findBySourceRow(CallLogImporter.SOURCE_ANDROID, 1)!!.linkedContactId)
        assertEquals(null, database.callLogDao().findBySourceRow(CallLogImporter.SOURCE_ANDROID, 2)!!.normalizedNumber)
    }

    @Test
    fun unknownContactIsStoredAsUnmatchedWithoutCrashing() = runTest {
        CallLogImporter(database).import(
            listOf(row(3, "139-0000-1234", CallLog.Calls.PRESENTATION_ALLOWED)),
            nowEpochMs = 3_000,
        )

        val imported = database.callLogDao().findBySourceRow(CallLogImporter.SOURCE_ANDROID, 3)!!
        assertEquals("13900001234", imported.normalizedNumber)
        assertEquals("UNMATCHED", imported.linkState)
        assertEquals(null, imported.linkedContactId)
    }

    @Test
    fun hangupPromptPersistsAndTypedNoteCreatesSensitiveFactProjection() = runTest {
        val importer = CallLogImporter(database)
        importer.import(listOf(row(7, "13800138000", CallLog.Calls.PRESENTATION_ALLOWED)), 3_000)
        val repository = CallLogRepository(database)

        val callId = repository.markLatestCallPending(nowEpochMs = 2_500)!!
        assertEquals(callId, repository.observePendingNotes().first().single().callRecordId)

        repository.saveTypedNote(callId, "周五前发送报价", nowEpochMs = 4_000)
        assertEquals("COMPLETED", database.callLogDao().findById(callId)!!.notePromptState)
        val fact = database.factDao().find("call-note:$callId")!!
        assertEquals("CALL_NOTE", fact.factType)
        assertEquals("SENSITIVE", fact.sensitivity)
        assertEquals("周五前发送报价", fact.textContent.removePrefix("通话备注："))

        assertEquals(true, repository.deleteNoteFact("call-note:$callId", nowEpochMs = 5_000))
        assertEquals(null, database.factDao().find("call-note:$callId"))
        assertEquals("DISMISSED", database.callLogDao().findById(callId)!!.notePromptState)
    }

    @Test
    fun pendingCallAndContactTimelineProjectActiveMergeAndUndoRestoresSource() = runTest {
        database.contactDao().insert(contact("canonical"))
        database.contactDao().insert(contact("source"))
        database.contactKnowledgeDao().upsertMethods(listOf(method("source", "13800138000")))
        CallLogImporter(database).import(listOf(row(8, "13800138000", CallLog.Calls.PRESENTATION_ALLOWED)), 3_000)
        val repository = CallLogRepository(database)
        val callId = repository.markLatestCallPending(nowEpochMs = 2_500)!!
        database.contactIdentityDao().upsertMergeLink(
            ContactMergeLinkEntity("source", "canonical", "同一人", true, 4_000, null),
        )

        assertEquals("canonical", repository.observePendingNotes().first().single().linkedContactId)
        assertEquals(callId, database.callLogDao().observeForContact("canonical").first().single().callRecordId)

        assertEquals(1, database.contactIdentityDao().undoConfirmedMerge("source", 5_000))
        assertEquals("source", repository.observePendingNotes().first().single().linkedContactId)
        assertEquals(callId, database.callLogDao().observeForContact("source").first().single().callRecordId)
    }

    @Test
    fun replayAfterImportCanRecoverMissingCrmSuggestionExactlyOnce() = runTest {
        database.contactDao().insert(contact("contact-1"))
        database.contactKnowledgeDao().upsertMethods(listOf(method("contact-1", "13800138000")))
        database.crmDao().insertOpportunity(opportunity("opportunity-1", "contact-1"))
        val call = row(9, "13800138000", CallLog.Calls.PRESENTATION_ALLOWED)
        CallLogImporter(database).import(listOf(call), nowEpochMs = 3_000)
        val crm = CrmAgentDataRepository(database)

        suggestCrmFollowUpsForSyncedCalls(database, crm, listOf(call), nowEpochMs = 4_000)
        suggestCrmFollowUpsForSyncedCalls(database, crm, listOf(call), nowEpochMs = 5_000)

        assertEquals(1, database.crmDao().observePendingSuggestions(0.0).first().size)
    }

    @Test
    fun failedCrmSuggestionRollsBackTheImportedCall() = runTest {
        database.contactDao().insert(contact("contact-1"))
        database.contactKnowledgeDao().upsertMethods(listOf(method("contact-1", "13800138000")))
        database.crmDao().insertOpportunity(opportunity("opportunity-1", "contact-1"))
        val call = row(10, "13800138000", CallLog.Calls.PRESENTATION_ALLOWED)
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_call_follow_up
            BEFORE INSERT ON crm_agent_suggestions
            WHEN NEW.suggestionType = 'CALL_FOLLOW_UP'
            BEGIN
                SELECT RAISE(ABORT, 'forced suggestion failure');
            END
            """.trimIndent(),
        )

        val failure = try {
            importCallsAndSuggestionsAtomically(
                database = database,
                crmRepository = CrmAgentDataRepository(database),
                rows = listOf(call),
                nowEpochMs = 4_000,
            )
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: RuntimeException) {
            error
        }

        assertNotNull(failure)
        assertNull(database.callLogDao().findBySourceRow(CallLogImporter.SOURCE_ANDROID, call.providerRowId))
        assertEquals(0, database.crmDao().observePendingSuggestions(0.0).first().size)
    }

    private fun contact(id: String) = ContactEntity(
        contactId = id, displayName = id, normalizedName = id, phone = null, email = null,
        wechatId = null, company = null, title = null, aliasesJson = "[]", tagsJson = "[]",
        note = null, avatarUri = null, source = "TEST", deletedAtEpochMs = null,
        createdAtEpochMs = 1, updatedAtEpochMs = 1,
    )

    private fun method(contactId: String, phone: String) = ContactMethodEntity(
        methodId = "method-$contactId", contactId = contactId, kind = "PHONE", value = phone,
        normalizedValue = phone, label = null, isPrimary = true, source = "TEST", evidenceRef = null,
        confidence = 1.0, userConfirmed = true, verifiedAtEpochMs = 1,
        createdAtEpochMs = 1, updatedAtEpochMs = 1,
    )

    private fun opportunity(id: String, contactId: String) = CrmOpportunityEntity(
        opportunityId = id,
        title = "测试机会",
        accountNameSnapshot = "测试客户",
        primaryContactId = contactId,
        sourceLeadId = null,
        stage = CrmOpportunityStage.QUALIFIED,
        status = CrmRecordStatus.OPEN,
        valueMinor = null,
        currencyCode = "CNY",
        probabilityPercent = CrmOpportunityStage.probabilityPercent(CrmOpportunityStage.QUALIFIED),
        expectedCloseAtEpochMs = null,
        productSummary = null,
        needSummary = null,
        lossReason = null,
        sourceType = "TEST",
        createdAtEpochMs = 1,
        updatedAtEpochMs = 1,
    )

    private fun row(id: Long, number: String, presentation: Int) = SystemCallLogRow(
        providerRowId = id, number = number, numberPresentation = presentation,
        systemType = CallLog.Calls.INCOMING_TYPE, startedAtEpochMs = 1_000,
        durationSeconds = 10, lastModifiedEpochMs = 2_000,
        phoneAccountId = null, phoneAccountComponentName = null,
    )
}
