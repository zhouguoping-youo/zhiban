package com.zhiban.rebuild.runtime.governance

import com.zhiban.rebuild.data.autowrite.AutoWriteAuditDraft
import com.zhiban.rebuild.data.autowrite.AutoWriteToolNames
import com.zhiban.rebuild.data.autowrite.canonicalChangeDigest

import com.zhiban.rebuild.data.autowrite.AutoWriteRepository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.contact.ContactEntity
import com.zhiban.rebuild.data.facts.FactEntity
import com.zhiban.rebuild.data.facts.FactIndex
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AutoWriteCorrectionTest {
    private lateinit var context: Context
    private lateinit var database: AgentDatabase

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AgentDatabase::class.java).allowMainThreadQueries().build()
        database.contactDao().insert(contact("old-contact", "原联系人"))
        database.contactDao().insert(contact("new-contact", "新联系人"))
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun correctingInteractionMovesTheSingleFactAndClosesOriginalUndo() = runBlocking {
        insertInteractionAutoWrite("change-correct")

        assertTrue(AutoWriteRepository(database, context).correctInteractionContact("change-correct", "new-contact", 20))

        val fact = requireNotNull(database.factDao().find("fact-interaction"))
        assertEquals("new-contact", fact.contactId)
        assertEquals("USER_CORRECTED", fact.sourceType)
        assertEquals(1, database.openHelper.writableDatabase.countFacts("fact-interaction"))
        assertEquals("UNAVAILABLE", database.changeLogDao().find("change-correct")?.undoState)
        assertEquals("CORRECTED", database.changeLogDao().findAutoWriteReceipt("change-correct")?.reviewState)
        assertNotNull(database.changeLogDao().findByIdempotencyKey(com.zhiban.rebuild.foundation.sha256("correction:change-correct:new-contact")))
    }

    @Test
    fun expiredAutoWriteCannotBeUndoneAndKeepsItsFact() = runBlocking {
        insertInteractionAutoWrite("change-expired")
        assertEquals(1, database.changeLogDao().expireUndoBefore(cutoffEpochMs = 11, limit = 10))

        assertTrue(!AutoWriteRepository(database, context).undo("change-expired", 20))
        assertNotNull(database.factDao().find("fact-interaction"))
        assertEquals("EXPIRED", database.changeLogDao().find("change-expired")?.undoState)
    }

    @Test
    fun visibleAutoWriteWithoutInverseIsRejectedAtomically() = runBlocking {
        val draft = auditDraft("change-invalid", afterDigest = "digest").copy(inversePayloadJson = "{}")

        val failure = runCatching { database.insertVisibleAutoWrite(draft) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals(null, database.changeLogDao().find("change-invalid"))
        assertEquals(null, database.changeLogDao().findAutoWriteReceipt("change-invalid"))
    }

    private suspend fun insertInteractionAutoWrite(changeId: String) {
        val fact = FactEntity(
            "fact-interaction", "INTERACTION_SUMMARY", "推断有一次沟通", null,
            "OBSERVED_NOTIFICATION", "candidate-1", "old-contact", null,
            0.7, "PERSONAL", "ACTIVE", 90, null, 10, 10,
        )
        FactIndex(database).upsert(fact)
        database.insertVisibleAutoWrite(auditDraft(changeId, canonicalChangeDigest(fact)))
    }

    private fun auditDraft(changeId: String, afterDigest: String) = AutoWriteAuditDraft(
        changeId = changeId,
        runtimeRunId = null,
        toolName = AutoWriteToolNames.INTERACTION_SUMMARY,
        idempotencyKey = "key-$changeId",
        targetDomain = "FACT",
        targetId = "fact-interaction",
        operation = "INSERT",
        afterDigest = afterDigest,
        inversePayloadJson = "{\"deleteFactId\":\"fact-interaction\"}",
        originType = "SYSTEM_PERCEPTION",
        subjectContactId = "old-contact",
        sourceType = "OBSERVED_NOTIFICATION",
        sourceRef = "candidate-1",
        confidence = 0.7,
        presentationType = "INTERACTION_SUMMARY",
        correctionRoute = "CONTACT_PICKER",
        createdAtEpochMs = 10,
    )

    private fun contact(id: String, name: String) = ContactEntity(
        id, name, name, null, null, null, null, null,
        "[]", "[]", null, null, "MANUAL", null, 1, 1,
    )

    private fun androidx.sqlite.db.SupportSQLiteDatabase.countFacts(factId: String): Int =
        query("SELECT COUNT(*) FROM facts WHERE factId = ?", arrayOf(factId)).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }
}
