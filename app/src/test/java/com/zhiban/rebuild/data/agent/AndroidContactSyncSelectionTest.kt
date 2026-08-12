package com.zhiban.rebuild.data.agent

import com.zhiban.rebuild.data.contact.ContactEntity
import com.zhiban.rebuild.data.contact.SystemContactCandidate
import com.zhiban.rebuild.data.contact.SystemContactDataRowSnapshot
import com.zhiban.rebuild.data.contact.SystemRawContactSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidContactSyncSelectionTest {
    @Test
    fun `writeback selects the writable raw contact carrying the matched phone`() {
        val unrelated = rawContact(1, "13900000000")
        val matched = rawContact(2, "138-0013-8000")
        val candidate = SystemContactCandidate(
            sourceId = "lookup",
            displayName = "测试联系人",
            phones = listOf("13900000000", "13800138000"),
            emails = emptyList(),
            company = null,
            title = null,
            rawContacts = listOf(unrelated, matched),
        )

        assertEquals(2L, selectWritableRawContact(candidate, contact())?.rawContactId)
    }

    private fun rawContact(id: Long, phone: String) = SystemRawContactSnapshot(
        rawContactId = id,
        aggregateContactId = 10,
        lookupKey = "lookup",
        accountName = null,
        accountType = null,
        sourceId = null,
        version = 1,
        isDirty = false,
        isReadOnly = false,
        dataRows = listOf(SystemContactDataRowSnapshot(id * 10, "phone", phone, false)),
    )

    private fun contact() = ContactEntity(
        contactId = "contact",
        displayName = "测试联系人",
        normalizedName = "测试联系人",
        phone = "13800138000",
        email = null,
        wechatId = null,
        company = null,
        title = null,
        aliasesJson = "[]",
        tagsJson = "[]",
        note = null,
        avatarUri = null,
        source = "TEST",
        deletedAtEpochMs = null,
        createdAtEpochMs = 1,
        updatedAtEpochMs = 1,
    )
}
