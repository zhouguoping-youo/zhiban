package com.zhiban.rebuild.data.contact

import android.content.Intent
import android.provider.ContactsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SystemContactWriteIntentTest {
    @Test
    fun importedContactEditsExistingRowWithoutPrefillingDuplicateFields() {
        val contact = contact(
            source = "SYSTEM_CONTACT:lookup-1",
            phone = "13800138000",
            email = "same@example.com",
            company = "知伴科技",
        )
        val existing = SystemContactCandidate(
            sourceId = "lookup-1",
            displayName = "测试联系人",
            phones = listOf("138 0013 8000"),
            emails = listOf("SAME@example.com"),
            company = "知伴科技",
            title = null,
            contactUri = "content://com.android.contacts/contacts/7",
        )

        val intent = SystemContactWriteIntent.create(contact, existing)

        assertEquals(Intent.ACTION_EDIT, intent.action)
        assertEquals("content://com.android.contacts/contacts/7", intent.dataString)
        assertNull(intent.getStringExtra(ContactsContract.Intents.Insert.PHONE))
        assertNull(intent.getStringExtra(ContactsContract.Intents.Insert.EMAIL))
        assertNull(intent.getStringExtra(ContactsContract.Intents.Insert.COMPANY))
        assertEquals("售前", intent.getStringExtra(ContactsContract.Intents.Insert.JOB_TITLE))
    }

    @Test
    fun matchingUsesStableSourceThenNormalizedPhoneAndEmail() {
        val bySource = candidate("source", listOf("10086"), listOf("source@example.com"))
        val byPhone = candidate("phone", listOf("138 0013 8000"), emptyList())
        val byEmail = candidate("email", emptyList(), listOf("same@example.com"))

        assertSame(bySource, findExistingSystemContact(contact(source = "SYSTEM_CONTACT:source"), listOf(byPhone, bySource)))
        assertSame(byPhone, findExistingSystemContact(contact(phone = "138-0013-8000"), listOf(byPhone)))
        assertSame(byEmail, findExistingSystemContact(contact(email = "SAME@example.com"), listOf(byEmail)))
    }

    @Test
    fun unmatchedContactStillUsesSystemOwnedInsertFlow() {
        val intent = SystemContactWriteIntent.create(contact(phone = "10086"))

        assertEquals(Intent.ACTION_INSERT, intent.action)
        assertEquals("10086", intent.getStringExtra(ContactsContract.Intents.Insert.PHONE))
    }

    private fun candidate(sourceId: String, phones: List<String>, emails: List<String>) = SystemContactCandidate(
        sourceId = sourceId,
        displayName = "测试联系人",
        phones = phones,
        emails = emails,
        company = null,
        title = null,
        contactUri = "content://com.android.contacts/contacts/$sourceId",
    )

    private fun contact(
        source: String = "MANUAL",
        phone: String? = null,
        email: String? = null,
        company: String? = null,
    ) = ContactEntity(
        contactId = "contact-1",
        displayName = "测试联系人",
        normalizedName = "测试联系人",
        phone = phone,
        email = email,
        wechatId = null,
        company = company,
        title = "售前",
        aliasesJson = "[]",
        tagsJson = "[]",
        note = null,
        avatarUri = null,
        source = source,
        deletedAtEpochMs = null,
        createdAtEpochMs = 1,
        updatedAtEpochMs = 1,
    )
}
