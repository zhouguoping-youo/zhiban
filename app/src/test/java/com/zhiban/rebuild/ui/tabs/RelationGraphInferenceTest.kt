package com.zhiban.rebuild.ui.tabs

import com.zhiban.rebuild.data.contact.ContactEntity
import com.zhiban.rebuild.data.contact.RelationshipEdgeEntity
import com.zhiban.rebuild.data.contact.RelationshipPersonIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelationGraphInferenceTest {
    @Test
    fun `same explicit company automatically links two contacts as inferred colleagues`() {
        val result = withInferredCompanyRelationships(
            contacts = listOf(
                contact("li", "李应啸", "西安知伴科技有限公司"),
                contact("ding", "丁波", "西安知伴科技有限公司"),
                contact("other", "其他人", "另一家公司"),
            ),
            ownerContactSources = emptyList(),
            savedEdges = emptyList(),
        )

        assertEquals(1, result.size)
        assertEquals(setOf("li", "ding"), setOf(result.single().fromContactId, result.single().toContactId))
        assertEquals("COLLEAGUE", result.single().relationType)
        assertTrue(result.single().isInferredCompanyRelationship())
        assertFalse(result.single().userConfirmed)
    }

    @Test
    fun `owner contact card participates as self and saved colleague edge is not duplicated`() {
        val savedSelfToLi = edge(RelationshipPersonIds.SELF, "li", "COLLEAGUE")

        val result = withInferredCompanyRelationships(
            contacts = listOf(
                contact("li", "李应啸", "西安知伴科技有限公司"),
                contact("ding", "丁波", "西安知伴科技有限公司"),
            ),
            ownerContactSources = listOf(contact("zhou", "周国平", "西安知伴科技有限公司")),
            savedEdges = listOf(savedSelfToLi),
        )

        assertEquals(3, result.size)
        assertEquals(1, result.count { unorderedPair(it) == setOf(RelationshipPersonIds.SELF, "li") })
        assertTrue(
            result.any {
                unorderedPair(it) == setOf(RelationshipPersonIds.SELF, "ding") &&
                    it.isInferredCompanyRelationship()
            },
        )
        assertTrue(result.any { unorderedPair(it) == setOf("li", "ding") && it.isInferredCompanyRelationship() })
    }

    @Test
    fun `company comparison ignores whitespace and latin letter case`() {
        val result = withInferredCompanyRelationships(
            contacts = listOf(
                contact("a", "A", "Open AI China"),
                contact("b", "B", " openai china "),
            ),
            ownerContactSources = emptyList(),
            savedEdges = emptyList(),
        )

        assertEquals(1, result.size)
    }

    @Test
    fun `different or generic short company values do not create a relationship`() {
        val result = withInferredCompanyRelationships(
            contacts = listOf(
                contact("a", "A", "公司"),
                contact("b", "B", "公司"),
                contact("c", "C", "甲方科技有限公司"),
                contact("d", "D", "乙方科技有限公司"),
            ),
            ownerContactSources = emptyList(),
            savedEdges = emptyList(),
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `same corporate email domain creates explainable inferred relationship`() {
        val result = withInferredCompanyRelationships(
            contacts = listOf(
                contact("a", "A", null, "a@zhiban.com"),
                contact("b", "B", null, "b@zhiban.com"),
                contact("c", "C", null, "c@qq.com"),
            ),
            ownerContactSources = emptyList(),
            savedEdges = emptyList(),
        )

        assertEquals(1, result.size)
        assertEquals(INFERRED_EMAIL_DOMAIN_RELATIONSHIP_STATUS, result.single().status)
        assertEquals("企业邮箱推测", result.single().inferredEvidenceLabel())
        assertTrue(result.single().evidenceDigest.contains("zhiban.com"))
    }

    @Test
    fun `work category uses inferred colleague edges while other categories stay distinct`() {
        val li = contact("li", "李应啸", "西安知伴科技有限公司")
        val inferred = withInferredCompanyRelationships(
            contacts = listOf(li, contact("ding", "丁波", "西安知伴科技有限公司")),
            ownerContactSources = emptyList(),
            savedEdges = emptyList(),
        )

        assertTrue(contactMatchesRelationCategory(li, "工作", inferred))
        assertFalse(contactMatchesRelationCategory(li, "家人", inferred))
        assertFalse(contactMatchesRelationCategory(li, "朋友", inferred))
        assertFalse(contactMatchesRelationCategory(li, "客户", inferred))
    }

    private fun contact(id: String, name: String, company: String?, email: String? = null) = ContactEntity(
        contactId = id,
        displayName = name,
        normalizedName = name.lowercase(),
        phone = null,
        email = email,
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

    private fun edge(from: String, to: String, type: String) = RelationshipEdgeEntity(
        edgeId = "$from-$to-$type",
        fromContactId = from,
        toContactId = to,
        relationType = type,
        evidenceDigest = "test",
        evidenceRefsJson = "[]",
        confidence = 1.0,
        userConfirmed = true,
        skillId = null,
        status = "ACTIVE",
        createdAtEpochMs = 1L,
        updatedAtEpochMs = 1L,
    )

    private fun unorderedPair(edge: RelationshipEdgeEntity) = setOf(edge.fromContactId, edge.toContactId)
}
