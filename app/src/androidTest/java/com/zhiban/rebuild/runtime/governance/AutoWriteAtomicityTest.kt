package com.zhiban.rebuild.runtime.governance

import com.zhiban.rebuild.data.autowrite.AutoWriteAuditDraft
import com.zhiban.rebuild.data.autowrite.AutoWriteToolNames

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.contact.ContactEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AutoWriteAtomicityTest {
    private lateinit var database: AgentDatabase

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AgentDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        database.contactDao().insert(
            ContactEntity(
                "contact-atomic", "测试联系人", "测试联系人", null, null, null, null, null,
                "[]", "[]", null, null, "MANUAL", null, 1, 1,
            ),
        )
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun injectedFailureRollsBackDomainChangeAuditAndReceiptTogether() = runBlocking {
        val failure = runCatching {
            database.withTransaction {
                val contact = requireNotNull(database.contactDao().findRawById("contact-atomic"))
                check(database.contactDao().update(contact.copy(tagsJson = "[\"客户\"]", updatedAtEpochMs = 2)) == 1)
                database.insertVisibleAutoWrite(
                    AutoWriteAuditDraft(
                        changeId = "change-atomic",
                        runtimeRunId = null,
                        toolName = AutoWriteToolNames.CONTACT_TAG_ADD,
                        idempotencyKey = "idempotency-atomic",
                        targetDomain = "CONTACT",
                        targetId = contact.contactId,
                        operation = "UPDATE",
                        beforeDigest = "before",
                        afterDigest = "after",
                        inversePayloadJson = "{\"removeTag\":\"客户\"}",
                        originType = "SYSTEM_PERCEPTION",
                        subjectContactId = contact.contactId,
                        sourceType = "TEST",
                        sourceRef = "test-source",
                        confidence = 1.0,
                        presentationType = "CONTACT_TAG",
                        correctionRoute = "CONTACT_TAG_EDITOR",
                        createdAtEpochMs = 2,
                    ),
                )
                error("INJECTED_AFTER_RECEIPT")
            }
        }.exceptionOrNull()

        assertEquals("INJECTED_AFTER_RECEIPT", failure?.message)
        assertEquals("[]", database.contactDao().findRawById("contact-atomic")?.tagsJson)
        assertNull(database.changeLogDao().find("change-atomic"))
        assertNull(database.changeLogDao().findAutoWriteReceipt("change-atomic"))
    }

    @Test
    fun receiptFailureRollsBackTheAuditWhenCalledWithoutAnOuterTransaction() = runBlocking {
        val failure = runCatching {
            database.insertVisibleAutoWrite(
                auditDraft(
                    changeId = "change-receipt-failure",
                    subjectContactId = "missing-contact",
                ),
            )
        }.exceptionOrNull()

        assertNotNull(failure)
        assertNull(database.changeLogDao().find("change-receipt-failure"))
        assertNull(database.changeLogDao().findAutoWriteReceipt("change-receipt-failure"))
    }

    private fun auditDraft(changeId: String, subjectContactId: String = "contact-atomic") =
        AutoWriteAuditDraft(
            changeId = changeId,
            runtimeRunId = null,
            toolName = AutoWriteToolNames.CONTACT_TAG_ADD,
            idempotencyKey = "idempotency-$changeId",
            targetDomain = "CONTACT",
            targetId = "contact-atomic",
            operation = "UPDATE",
            beforeDigest = "before",
            afterDigest = "after",
            inversePayloadJson = "{\"removeTag\":\"客户\"}",
            originType = "SYSTEM_PERCEPTION",
            subjectContactId = subjectContactId,
            sourceType = "TEST",
            sourceRef = "test-source",
            confidence = 1.0,
            presentationType = "CONTACT_TAG",
            correctionRoute = "CONTACT_TAG_EDITOR",
            createdAtEpochMs = 2,
        )
}
