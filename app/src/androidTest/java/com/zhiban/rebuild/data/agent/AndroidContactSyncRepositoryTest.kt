package com.zhiban.rebuild.data.agent

import android.Manifest
import android.content.ContentProviderOperation
import android.content.Context
import android.os.Build
import android.provider.ContactsContract
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.zhiban.rebuild.data.contact.ContactEntity
import com.zhiban.rebuild.data.contact.SystemContactReader
import com.zhiban.rebuild.data.contact.normalizeContactPhone
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidContactSyncRepositoryTest {
    @get:Rule
    val contactsPermission: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.WRITE_CONTACTS,
    )

    private lateinit var context: Context
    private lateinit var database: AgentDatabase
    private lateinit var reader: SystemContactReader
    private var rawContactId: Long? = null
    private lateinit var testPhone: String
    private lateinit var testSecondPhone: String

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AgentDatabase::class.java).build()
        reader = SystemContactReader(context)
        val uniqueSuffix = (System.nanoTime() % 100_000_000L).toString().padStart(8, '0')
        testPhone = "139$uniqueSuffix"
        testSecondPhone = "138$uniqueSuffix"
        rawContactId = createSystemContact()
    }

    @After
    fun tearDown() {
        rawContactId?.let { id ->
            context.contentResolver.delete(
                ContactsContract.RawContacts.CONTENT_URI,
                "${ContactsContract.RawContacts._ID} = ?",
                arrayOf(id.toString()),
            )
        }
        database.close()
    }

    @Test
    fun confirmedWriteIsVerifiedAuditedAndReversible() = runBlocking {
        val candidate = awaitCreatedContact()
        assertTrue(
            "the local test raw contact must remain writable regardless of OEM account metadata",
            candidate.rawContacts.any { it.rawContactId == rawContactId && !it.isReadOnly },
        )
        val contact = contact(candidate.sourceId, company = "新公司", title = "负责人", secondPhone = testSecondPhone)
        database.withTransaction {
            database.contactDao().insert(contact)
            database.upsertObservedSystemContactIntelligence(
                candidate = candidate,
                contact = contact.copy(phone = testPhone, company = "旧公司", title = "销售"),
                sourceRef = "android-contact:${candidate.sourceId}",
                nowEpochMs = 10,
            )
        }
        val repository = AndroidContactSyncRepository(context, database, reader)

        val preview = repository.prepare(contact)
        assertTrue(preview.plan.canApply)
        assertEquals("新公司", preview.plan.scalarUpdates["company"])
        assertEquals(listOf(testSecondPhone), preview.plan.phoneAdditions)

        val result = repository.apply(preview, nowEpochMs = 20)
        val updated = reader.readAll().contacts.single { it.sourceId == candidate.sourceId }
        assertEquals("新公司", updated.company)
        assertEquals("负责人", updated.title)
        assertTrue(testSecondPhone in updated.phones)
        assertEquals("APPLIED", database.contactIntelligenceDao().findSyncOperation(result.operationId)?.state)
        assertEquals("AVAILABLE", database.changeLogDao().find(result.operationId)?.undoState)

        repository.undo(result.operationId, nowEpochMs = 30)
        val restored = reader.readAll().contacts.single { it.sourceId == candidate.sourceId }
        assertEquals("旧公司", restored.company)
        assertEquals("销售", restored.title)
        assertTrue(testSecondPhone !in restored.phones)
        assertEquals("UNDONE", database.contactIntelligenceDao().findSyncOperation(result.operationId)?.state)
    }

    private fun createSystemContact(): Long {
        val operations = arrayListOf<ContentProviderOperation>()
        val localAccountName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ContactsContract.RawContacts.getLocalAccountName(context)
        } else {
            null
        }
        val localAccountType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ContactsContract.RawContacts.getLocalAccountType(context)
        } else {
            null
        }
        operations += ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
            .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, localAccountType)
            .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, localAccountName)
            .build()
        operations += dataInsert(ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
            .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, TEST_NAME)
            .build()
        operations += dataInsert(ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
            .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, testPhone)
            .build()
        operations += dataInsert(ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE)
            .withValue(ContactsContract.CommonDataKinds.Organization.COMPANY, "旧公司")
            .withValue(ContactsContract.CommonDataKinds.Organization.TITLE, "销售")
            .build()
        return context.contentResolver.applyBatch(ContactsContract.AUTHORITY, operations)[0]
            .uri?.lastPathSegment?.toLongOrNull() ?: error("未创建测试联系人")
    }

    private fun dataInsert(mimeType: String): ContentProviderOperation.Builder = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
        .withValue(ContactsContract.Data.MIMETYPE, mimeType)

    private suspend fun awaitCreatedContact(): com.zhiban.rebuild.data.contact.SystemContactCandidate {
        var lastResult: com.zhiban.rebuild.data.contact.SystemContactReadResult? = null
        repeat(20) {
            val result = reader.readAll()
            lastResult = result
            result.contacts.firstOrNull { candidate ->
                candidate.phones.any { normalizeContactPhone(it) == testPhone }
            }?.let { return it }
            delay(100)
        }
        error(
            "测试联系人未出现在系统通讯录：raw=$rawContactId, rows=${lastResult?.rowsRead}, " +
                "contacts=${lastResult?.contacts?.size}, error=${lastResult?.errorMessage}",
        )
    }

    private fun contact(sourceId: String, company: String, title: String, secondPhone: String) = ContactEntity(
        contactId = "sync-test-contact",
        displayName = TEST_NAME,
        normalizedName = TEST_NAME,
        phone = secondPhone,
        email = null,
        wechatId = null,
        company = company,
        title = title,
        aliasesJson = "[]",
        tagsJson = "[]",
        note = null,
        avatarUri = null,
        source = "SYSTEM_CONTACT:$sourceId",
        deletedAtEpochMs = null,
        createdAtEpochMs = 1,
        updatedAtEpochMs = 1,
    )

    private companion object {
        const val TEST_NAME = "知伴同步测试"
    }
}
