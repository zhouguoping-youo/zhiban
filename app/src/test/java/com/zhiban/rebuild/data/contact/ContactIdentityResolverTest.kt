package com.zhiban.rebuild.data.contact

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactIdentityResolverTest {
    @Test
    fun twoIndependentExactIdentifiersAllowReversibleAutoLink() {
        val first = contact("a", "丁波", phone = "138-0013-8000", email = "ding@example.com")
        val second = contact("b", "老丁", phone = "13800138000", email = "DING@example.com")

        val result = ContactIdentityResolver.resolve(listOf(first, second), emptyList(), emptyList()).single()

        assertEquals(IdentityResolutionDecision.AUTO_LINK, result.decision)
        assertEquals("手机号、邮箱相同", result.reason)
    }

    @Test
    fun nameAndCompanyAloneNeverAutoLink() {
        val result = ContactIdentityResolver.resolve(
            listOf(contact("a", "张三", company = "知伴"), contact("b", "张三", company = "知伴")),
            emptyList(),
            emptyList(),
        ).single()

        assertEquals(IdentityResolutionDecision.REVIEW, result.decision)
    }

    @Test
    fun oneSharedPhoneWithConflictingEmailRequiresReview() {
        val first = contact("a", "张三", phone = "13800138000", email = "a@example.com")
        val second = contact("b", "张三", phone = "13800138000", email = "b@example.com")

        val result = ContactIdentityResolver.resolve(listOf(first, second), emptyList(), emptyList()).single()

        assertEquals(IdentityResolutionDecision.REVIEW, result.decision)
        assertEquals(1, result.contradictions)
    }

    @Test
    fun sameStablePlatformUserIdAllowsAutoLink() {
        val contacts = listOf(contact("a", "群昵称甲"), contact("b", "通讯录姓名"))
        val identities = contacts.map { value ->
            ContactPlatformIdentityEntity(
                identityId = "id-${value.contactId}",
                contactId = value.contactId,
                platform = "WECHAT",
                handle = value.displayName,
                normalizedHandle = value.normalizedName,
                platformUserId = "wx-stable-42",
                source = "OBSERVED",
                userConfirmed = false,
                createdAtEpochMs = 1,
                updatedAtEpochMs = 1,
            )
        }

        val result = ContactIdentityResolver.resolve(contacts, emptyList(), identities).single()

        assertEquals(IdentityResolutionDecision.AUTO_LINK, result.decision)
    }

    @Test
    fun sameNameWithMultipleContradictionsIsBlocked() {
        val first = contact("a", "张三", "13800138000", "a@example.com", "知伴")
        val second = contact("b", "张三", "13900139000", "b@example.com", "知伴")

        val result = ContactIdentityResolver.resolve(listOf(first, second), emptyList(), emptyList()).single()

        assertEquals(IdentityResolutionDecision.BLOCKED, result.decision)
        assertTrue(result.contradictions >= 2)
    }

    @Test
    fun largeAddressBookUsesEvidenceIndexesInsteadOfComparingEveryPair() {
        val contacts = (0 until 10_000).map { index ->
            contact("contact-$index", "联系人$index", phone = "139${index.toString().padStart(8, '0')}")
        } + listOf(
            contact("duplicate-a", "甲", phone = "13800138000", email = "same@example.com"),
            contact("duplicate-b", "乙", phone = "138-0013-8000", email = "SAME@example.com"),
        )

        val result = ContactIdentityResolver.resolve(contacts, emptyList(), emptyList())

        assertEquals(1, result.size)
        assertEquals(IdentityResolutionDecision.AUTO_LINK, result.single().decision)
    }

    private fun contact(id: String, name: String, phone: String? = null, email: String? = null, company: String? = null) = ContactEntity(
        contactId = id,
        displayName = name,
        normalizedName = name.lowercase(),
        phone = phone,
        email = email,
        wechatId = null,
        company = company,
        title = null,
        aliasesJson = "[]",
        tagsJson = "[]",
        note = null,
        avatarUri = null,
        source = "SYSTEM_CONTACT",
        deletedAtEpochMs = null,
        createdAtEpochMs = 1,
        updatedAtEpochMs = 1,
    )
}
