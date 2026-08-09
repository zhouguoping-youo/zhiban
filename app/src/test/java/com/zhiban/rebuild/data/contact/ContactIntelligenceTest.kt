package com.zhiban.rebuild.data.contact

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactIntelligenceTest {
    @Test
    fun uniqueLegalCompanyNameCompletesLocalAbbreviationAsCandidate() {
        val suggestions = buildLocalOrganizationSuggestions(
            listOf(contact("a", "周国平", "知伴"), contact("b", "李应啸", "知伴科技（上海）有限公司")),
        )

        assertEquals(1, suggestions.size)
        assertEquals("a", suggestions.single().contactId)
        assertEquals("知伴科技（上海）有限公司", suggestions.single().company)
        assertTrue(suggestions.single().confidence < 1.0)
    }

    @Test
    fun ambiguousCompanyNamesAreNotSuggested() {
        val suggestions = buildLocalOrganizationSuggestions(
            listOf(
                contact("a", "周国平", "知伴"),
                contact("b", "李应啸", "知伴科技（上海）有限公司"),
                contact("c", "丁波", "知伴咨询有限公司"),
            ),
        )

        assertTrue(suggestions.isEmpty())
    }

    @Test
    fun onlyCorporateEmailDomainsBecomeRelationshipEvidence() {
        assertEquals("zhiban.com", corporateEmailDomain("zhou@zhiban.com"))
        assertNull(corporateEmailDomain("zhou@qq.com"))
        assertNull(corporateEmailDomain("not-an-email"))
    }

    private fun contact(id: String, name: String, company: String?) = ContactEntity(
        contactId = id,
        displayName = name,
        normalizedName = name.lowercase(),
        phone = null,
        email = null,
        wechatId = null,
        company = company,
        title = null,
        aliasesJson = "[]",
        tagsJson = "[]",
        note = null,
        avatarUri = null,
        source = "TEST",
        deletedAtEpochMs = null,
        createdAtEpochMs = 1L,
        updatedAtEpochMs = 1L,
    )
}
