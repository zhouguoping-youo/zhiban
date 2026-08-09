package com.zhiban.rebuild.ui.tabs

import com.zhiban.rebuild.data.contact.ContactEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelationPhoneMatchingTest {
    @Test
    fun formattedAndCanonicalPhoneNumbersMatch() {
        assertTrue(sameNormalizedPhone("138-0013-8000", "13800138000"))
        assertTrue(sameNormalizedPhone("+86 138 0013 8000", "+8613800138000"))
        assertTrue(sameNormalizedPhone("+86 138 0013 8000", "13800138000"))
    }

    @Test
    fun blankShortAndDifferentPhoneNumbersDoNotMatch() {
        assertFalse(sameNormalizedPhone(null, "13800138000"))
        assertFalse(sameNormalizedPhone("1234", "1234"))
        assertFalse(sameNormalizedPhone("13800138000", "13900139000"))
    }

    @Test
    fun mergeSuggestionIndexFindsOnlyTheActualDuplicateInALargeContactSet() {
        val contacts =
            (0 until 1_000).map { index ->
                contact("contact-$index", "联系人$index", "139${index.toString().padStart(8, '0')}")
            } +
                contact("formatted", "格式号码", "138-0013-8000") +
                contact("canonical", "标准号码", "13800138000")

        val suggestions = buildMergeSuggestions(contacts, emptyList(), emptyList(), emptyList())

        assertTrue(
            suggestions.any {
                setOf(it.first.contactId, it.second.contactId) == setOf("formatted", "canonical") &&
                    it.reason == "手机号相同"
            },
        )
        assertTrue(
            suggestions.none {
                it.first.contactId.startsWith("contact-") ||
                    it.second.contactId.startsWith("contact-")
            },
        )
    }

    @Test
    fun sameNameWithOneAgentStubSuggestsMerge() {
        // agent 据对话先建的占位（AGENT_CANDIDATE，只有名字），随后从通讯录导入同一个真人（有手机号）。
        val stub = entry("agent-ding", "丁波", null, source = "AGENT_CANDIDATE")
        val real = entry("system-ding", "丁波", "13800138000", source = "SYSTEM_CONTACT")
        val suggestions = buildMergeSuggestions(listOf(stub, real), emptyList(), emptyList(), emptyList())
        val hit = suggestions.firstOrNull {
            setOf(it.first.contactId, it.second.contactId) == setOf("agent-ding", "system-ding")
        }
        assertTrue("expected same-name stub merge suggestion", hit != null)
        assertEquals("同名且一方是待确认联系人", hit!!.reason)
    }

    @Test
    fun sameNameAgentStubAndRealWithNoContactInfoSuggestsMerge() {
        // 同上的边界：真人恰好也没存手机号/邮箱。占位仍是 AGENT_CANDIDATE，所以应出建议。
        val stub = entry("agent-ding", "丁波", null, source = "AGENT_CANDIDATE")
        val realNoInfo = entry("system-ding", "丁波", null, source = "SYSTEM_CONTACT")
        val suggestions = buildMergeSuggestions(listOf(stub, realNoInfo), emptyList(), emptyList(), emptyList())
        assertTrue(
            suggestions.any {
                setOf(it.first.contactId, it.second.contactId) == setOf("agent-ding", "system-ding") &&
                    it.reason == "同名且一方是待确认联系人"
            },
        )
    }

    @Test
    fun sameNameTwoRealContactsWithNoContactInfoDoesNotSuggestByName() {
        // 误合并防护：两个都是真实导入联系人（SYSTEM_CONTACT，都没联系方式），仅同名 -> 不出建议。
        val a = entry("zhang-a", "张三", null, source = "SYSTEM_CONTACT")
        val b = entry("zhang-b", "张三", null, source = "SYSTEM_CONTACT")
        val suggestions = buildMergeSuggestions(listOf(a, b), emptyList(), emptyList(), emptyList())
        assertTrue(suggestions.none { it.reason == "同名且一方是待确认联系人" })
    }

    @Test
    fun sameNameUserEnteredAndRealContactDoesNotSuggestByName() {
        // 用户手动建的联系人（source=USER）不是 agent 占位：与同名真人仅同名 -> 不出建议。
        val userMade = entry("zhang-user", "张三", null, source = "USER")
        val real = entry("zhang-sys", "张三", null, source = "SYSTEM_CONTACT")
        val suggestions = buildMergeSuggestions(listOf(userMade, real), emptyList(), emptyList(), emptyList())
        assertTrue(suggestions.none { it.reason == "同名且一方是待确认联系人" })
    }

    @Test
    fun sameNameTwoContactsBothWithPhonesDoesNotSuggestByName() {
        // 两个真人都同名且各有不同手机号 -> 不应仅凭同名出建议（避免把同名不同人误凑）。
        val a = entry("ding-a", "丁波", "13800138000")
        val b = entry("ding-b", "丁波", "13900139000")
        val suggestions = buildMergeSuggestions(listOf(a, b), emptyList(), emptyList(), emptyList())
        assertTrue(suggestions.none { it.reason == "同名且一方是待确认联系人" })
    }

    private fun entry(id: String, name: String, phone: String?, source: String = "USER") = ContactEntity(
        contactId = id,
        displayName = name,
        normalizedName = name,
        phone = phone,
        email = null,
        wechatId = null,
        company = null,
        title = null,
        aliasesJson = "[]",
        tagsJson = "[]",
        note = null,
        avatarUri = null,
        source = source,
        deletedAtEpochMs = null,
        createdAtEpochMs = 1,
        updatedAtEpochMs = 1,
    )

    private fun contact(id: String, name: String, phone: String) = ContactEntity(
        contactId = id,
        displayName = name,
        normalizedName = name,
        phone = phone,
        email = null,
        wechatId = null,
        company = null,
        title = null,
        aliasesJson = "[]",
        tagsJson = "[]",
        note = null,
        avatarUri = null,
        source = "USER",
        deletedAtEpochMs = null,
        createdAtEpochMs = 1,
        updatedAtEpochMs = 1,
    )
}
