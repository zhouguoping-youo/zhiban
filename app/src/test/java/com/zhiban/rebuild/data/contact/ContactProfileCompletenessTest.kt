package com.zhiban.rebuild.data.contact

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactProfileCompletenessTest {

    @Test fun wechatStubCountsWechatAsReachableViaPlatformIdentity() {
        // 微信 stub:只有显示名,phone/company/title/responsibilities/email 全空,但有一条 WECHAT 平台身份(handle=显示名)。
        val stub = contact("s1", name = "张三")
        val completeness = ContactProfileCompletenessEvaluator.evaluate(stub, listOf(wechatIdentity("s1")))

        // 微信经 platform identity 判可达、不算缺;缺的是其余五项。
        assertEquals(
            listOf(
                ContactProfileField.PHONE,
                ContactProfileField.COMPANY,
                ContactProfileField.TITLE,
                ContactProfileField.RESPONSIBILITIES,
                ContactProfileField.EMAIL,
            ),
            completeness.missingFields,
        )
        assertFalse(completeness.missingFields.contains(ContactProfileField.WECHAT))
        assertFalse(completeness.missingFields.contains(ContactProfileField.NAME))
    }

    @Test fun wechatMissingWhenNeitherWechatIdNorIdentity() {
        val stub = contact("s1", name = "张三")
        val completeness = ContactProfileCompletenessEvaluator.evaluate(stub, emptyList())

        assertTrue(completeness.missingFields.contains(ContactProfileField.WECHAT))
    }

    @Test fun wechatIdAloneSatisfiesWechatWithoutIdentity() {
        val contact = contact("s1", wechatId = "wxid_123")
        val completeness = ContactProfileCompletenessEvaluator.evaluate(contact, emptyList())

        assertFalse(completeness.missingFields.contains(ContactProfileField.WECHAT))
    }

    @Test fun fullyFilledContactIsComplete() {
        val contact = contact(
            "s1",
            phone = "13800138000",
            wechatId = "wxid",
            company = "知伴科技",
            title = "经理",
            responsibilities = "采购",
            email = "a@b.com",
        )
        val completeness = ContactProfileCompletenessEvaluator.evaluate(contact, emptyList())

        assertTrue(completeness.isComplete)
        assertTrue(completeness.missingFields.isEmpty())
    }

    @Test fun missingFieldsFollowEnumDeclarationOrder() {
        val contact = contact("s1", name = "张三", phone = "13800138000", wechatId = "wx")
        val completeness = ContactProfileCompletenessEvaluator.evaluate(contact, emptyList())

        assertEquals(
            listOf(
                ContactProfileField.COMPANY,
                ContactProfileField.TITLE,
                ContactProfileField.RESPONSIBILITIES,
                ContactProfileField.EMAIL,
            ),
            completeness.missingFields,
        )
    }

    @Test fun incompleteFiltersOutCompleteAndSortsFewestMissingFirst() {
        val full = contact("full", name = "全", phone = "1", wechatId = "w", company = "c", title = "t", responsibilities = "r", email = "e")
        val almostDone = contact("almost", name = "几乎", phone = "1", wechatId = "w", company = "c", title = "t", email = "e") // 只缺职责
        val stub = contact("stub", name = "stub张") // 缺很多
        val result = ContactProfileCompletenessEvaluator.incomplete(
            contacts = listOf(stub, full, almostDone),
            platformIdentities = listOf(wechatIdentity("stub"), wechatIdentity("almost"), wechatIdentity("full")),
        )

        // full 已完整被过滤;almostDone(缺 1)排在 stub(缺 5)前。
        assertEquals(listOf("almost", "stub"), result.map { it.contact.contactId })
        assertEquals(listOf(ContactProfileField.RESPONSIBILITIES), result.first().missingFields)
    }

    private fun contact(
        id: String,
        name: String = "联系人$id",
        phone: String? = null,
        wechatId: String? = null,
        company: String? = null,
        title: String? = null,
        responsibilities: String? = null,
        email: String? = null,
    ) = ContactEntity(
        contactId = id,
        displayName = name,
        normalizedName = name,
        phone = phone,
        email = email,
        wechatId = wechatId,
        company = company,
        title = title,
        aliasesJson = "[]",
        tagsJson = "[]",
        note = null,
        avatarUri = null,
        source = "USER",
        deletedAtEpochMs = null,
        createdAtEpochMs = 1,
        updatedAtEpochMs = 1,
        responsibilities = responsibilities,
    )

    private fun wechatIdentity(contactId: String, handle: String = "联系人$contactId") = ContactPlatformIdentityEntity(
        identityId = "id-$contactId",
        contactId = contactId,
        platform = "WECHAT",
        handle = handle,
        normalizedHandle = handle,
        platformUserId = null,
        source = "NOTIFICATION",
        userConfirmed = false,
        createdAtEpochMs = 1,
        updatedAtEpochMs = 1,
    )
}
