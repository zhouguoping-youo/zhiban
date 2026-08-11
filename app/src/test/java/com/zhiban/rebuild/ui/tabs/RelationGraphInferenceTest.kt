package com.zhiban.rebuild.ui.tabs

import com.zhiban.rebuild.data.contact.ContactEntity
import com.zhiban.rebuild.data.contact.PersonEmploymentEpisodeEntity
import com.zhiban.rebuild.data.contact.RelationshipEdgeEntity
import com.zhiban.rebuild.data.contact.RelationshipEpisodeEntity
import com.zhiban.rebuild.data.contact.RelationshipPersonIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelationGraphInferenceTest {
    @Test
    fun `same explicit company automatically links two contacts as inferred colleagues`() {
        val contacts = listOf(
            contact("li", "李应啸", "西安知伴科技有限公司"),
            contact("ding", "丁波", "西安知伴科技有限公司"),
            contact("other", "其他人", "另一家公司"),
        )
        val result = withInferredCompanyRelationships(
            contacts = contacts,
            ownerContactSources = emptyList(),
            savedEdges = emptyList(),
            employmentEpisodes = employmentsFor(contacts),
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

        val contacts = listOf(
            contact("li", "李应啸", "西安知伴科技有限公司"),
            contact("ding", "丁波", "西安知伴科技有限公司"),
        )
        val owner = contact("zhou", "周国平", "西安知伴科技有限公司")
        val result = withInferredCompanyRelationships(
            contacts = contacts,
            ownerContactSources = listOf(owner),
            savedEdges = listOf(savedSelfToLi),
            employmentEpisodes = employmentsFor(contacts + owner),
        )

        assertEquals(2, result.size)
        assertEquals(1, result.count { unorderedPair(it) == setOf(RelationshipPersonIds.SELF, "li") })
        assertTrue(
            result.any {
                unorderedPair(it) == setOf(RelationshipPersonIds.SELF, "ding") &&
                    it.isInferredCompanyRelationship()
            },
        )
        assertFalse(result.any { unorderedPair(it) == setOf("li", "ding") && it.isInferredCompanyRelationship() })
    }

    @Test
    fun `company comparison ignores whitespace and latin letter case`() {
        val contacts = listOf(
            contact("a", "A", "Open AI China"),
            contact("b", "B", " openai china "),
        )
        val result = withInferredCompanyRelationships(
            contacts = contacts,
            ownerContactSources = emptyList(),
            savedEdges = emptyList(),
            employmentEpisodes = employmentsFor(contacts),
        )

        assertEquals(1, result.size)
    }

    @Test
    fun `different or generic short company values do not create a relationship`() {
        val contacts = listOf(
            contact("a", "A", "公司"),
            contact("b", "B", "公司"),
            contact("c", "C", "甲方科技有限公司"),
            contact("d", "D", "乙方科技有限公司"),
        )
        val result = withInferredCompanyRelationships(
            contacts = contacts,
            ownerContactSources = emptyList(),
            savedEdges = emptyList(),
            employmentEpisodes = employmentsFor(contacts),
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
            employmentEpisodes = employmentsFor(listOf(li, contact("ding", "丁波", "西安知伴科技有限公司"))),
        )

        assertTrue(contactMatchesRelationCategory(li, "工作", inferred))
        assertFalse(contactMatchesRelationCategory(li, "家人", inferred))
        assertFalse(contactMatchesRelationCategory(li, "朋友", inferred))
        assertFalse(contactMatchesRelationCategory(li, "客户", inferred))
    }

    @Test
    fun `large company produces a sparse connected graph instead of a quadratic clique`() {
        val contacts = (1..500).map { index ->
            contact("person-$index", "联系人$index", "知伴科技有限公司")
        }

        val result = withInferredCompanyRelationships(
            contacts = contacts,
            ownerContactSources = emptyList(),
            savedEdges = emptyList(),
            employmentEpisodes = employmentsFor(contacts),
        )

        assertEquals(499, result.size)
        assertEquals(500, result.flatMap { listOf(it.fromContactId, it.toContactId) }.toSet().size)
    }

    @Test
    fun `owner graph does not pretend unrelated contact edges are connected to self`() {
        val contactEdges = listOf(
            edge("a", "b", "COLLEAGUE"),
            edge("b", "c", "PROJECT_PARTNER"),
        )

        assertTrue(relationshipGraphEdgesForRoot(RelationshipPersonIds.SELF, contactEdges).isEmpty())
        assertEquals(listOf(contactEdges.first()), relationshipGraphEdgesForRoot("a", contactEdges))
    }

    @Test
    fun `confirmed owner employment anchors matching contact to self`() {
        val contact = contact("colleague", "同事甲", "知伴科技有限公司")
        val result = withInferredCompanyRelationships(
            contacts = listOf(contact),
            ownerContactSources = emptyList(),
            savedEdges = emptyList(),
            employmentEpisodes = listOf(
                employment(RelationshipPersonIds.SELF, "知伴科技有限公司", null, null, currentState = "CURRENT"),
                employment(contact.contactId, "知伴科技有限公司", null, null),
            ),
        )

        assertEquals(1, result.size)
        assertEquals(setOf(RelationshipPersonIds.SELF, contact.contactId), unorderedPair(result.single()))
        assertEquals("同公司", result.single().displayRelationLabel())
    }

    @Test
    fun `overlapping past employments are labelled former colleagues`() {
        val result = withInferredCompanyRelationships(
            contacts = listOf(contact("past", "前同事", "知伴科技有限公司")),
            ownerContactSources = emptyList(),
            savedEdges = emptyList(),
            employmentEpisodes = listOf(
                employment(RelationshipPersonIds.SELF, "知伴科技有限公司", 1_000L, 3_000L, currentState = "PAST"),
                employment("past", "知伴科技有限公司", 2_000L, 4_000L, currentState = "PAST"),
            ),
        )

        assertEquals(INFERRED_HISTORICAL_COMPANY_RELATIONSHIP_STATUS, result.single().status)
        assertEquals("前同事", result.single().displayRelationLabel())
    }

    @Test
    fun `past company without complete dates is shown as possible former colleague`() {
        val result = withInferredCompanyRelationships(
            contacts = listOf(contact("past", "旧公司联系人", "知伴科技有限公司")),
            ownerContactSources = emptyList(),
            savedEdges = emptyList(),
            employmentEpisodes = listOf(
                employment(RelationshipPersonIds.SELF, "知伴科技有限公司", null, 3_000L, currentState = "PAST"),
                employment("past", "知伴科技有限公司", null, null),
            ),
        )

        assertEquals(INFERRED_HISTORICAL_COMPANY_UNKNOWN_TIME_STATUS, result.single().status)
        assertEquals("可能是前同事", result.single().displayRelationLabel())
        assertEquals("曾在同公司 · 时间待核实", result.single().inferredEvidenceLabel())
    }

    @Test
    fun `non-overlapping employment periods do not infer colleagues`() {
        val contacts = listOf(contact("old", "前员工", "知伴科技有限公司"), contact("new", "现员工", "知伴科技有限公司"))
        val episodes = listOf(
            employment("old", "知伴科技有限公司", 1_000L, 2_000L),
            employment("new", "知伴科技有限公司", 3_000L, null),
        )

        val result = withInferredCompanyRelationships(contacts, emptyList(), emptyList(), episodes)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `unknown employment time is labelled for verification instead of claimed current`() {
        val contacts = listOf(contact("a", "甲", "知伴科技有限公司"), contact("b", "乙", "知伴科技有限公司"))

        val result = withInferredCompanyRelationships(contacts, emptyList(), emptyList(), employmentsFor(contacts))

        assertEquals(INFERRED_COMPANY_UNKNOWN_TIME_STATUS, result.single().status)
        assertEquals("同公司 · 时间待核实", result.single().inferredEvidenceLabel())
    }

    @Test
    fun `only closed temporal episodes enter historical graph layer`() {
        val history = historicalRelationshipEdges(
            listOf(
                relationshipEpisode("past", "FRIEND", 200L),
                relationshipEpisode("current", "COLLEAGUE", null),
            ),
        )

        assertEquals(1, history.size)
        assertEquals("HISTORICAL", history.single().status)
        assertEquals("FRIEND", history.single().relationType)
    }

    @Test
    fun `current and historical relationships share one graph without duplicate type`() {
        val currentColleague = edge("self", "contact", "COLLEAGUE")
        val historicalColleague = currentColleague.copy(edgeId = "history-colleague", status = "HISTORICAL")
        val historicalFriend = edge("self", "contact", "FRIEND").copy(
            edgeId = "history-friend",
            status = "HISTORICAL",
        )

        val result = mergeCurrentAndHistoricalRelationships(
            current = listOf(currentColleague),
            historical = listOf(historicalColleague, historicalFriend),
        )

        assertEquals(2, result.size)
        assertEquals(1, result.count { it.relationType == "COLLEAGUE" })
        assertTrue(result.any { it.relationType == "FRIEND" && it.status == "HISTORICAL" })
    }

    @Test
    fun `expanded taxonomy participates in existing category filters`() {
        val person = contact("person", "联系人", null)
        val relationships = listOf(
            edge(RelationshipPersonIds.SELF, person.contactId, "MANAGER"),
            edge(RelationshipPersonIds.SELF, person.contactId, "SPOUSE"),
            edge(RelationshipPersonIds.SELF, person.contactId, "CLOSE_FRIEND"),
        )

        assertTrue(contactMatchesRelationCategory(person, "工作", relationships))
        assertTrue(contactMatchesRelationCategory(person, "家人", relationships))
        assertTrue(contactMatchesRelationCategory(person, "朋友", relationships))
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

    private fun employmentsFor(contacts: List<ContactEntity>) = contacts.mapNotNull { contact ->
        contact.company?.let { employment(contact.contactId, it, null, null) }
    }

    private fun employment(personId: String, company: String, from: Long?, to: Long?, currentState: String = if (to == null) "UNKNOWN" else "ENDED") =
        PersonEmploymentEpisodeEntity(
            episodeId = "employment-$personId-$company",
            personId = personId,
            organizationId = null,
            companyNameSnapshot = company,
            department = null,
            title = null,
            validFromEpochMs = from,
            validToEpochMs = to,
            temporalPrecision = if (from == null && to == null) "UNKNOWN" else "DAY",
            currentState = currentState,
            sourceRef = "test",
            confidence = 0.8,
            verificationState = "OBSERVED",
            status = "ACTIVE",
            recordedAtEpochMs = 1L,
            updatedAtEpochMs = 1L,
        )

    private fun relationshipEpisode(id: String, type: String, validTo: Long?) = RelationshipEpisodeEntity(
        episodeId = id,
        fromPersonId = RelationshipPersonIds.SELF,
        toPersonId = "contact",
        relationshipType = type,
        direction = "BIDIRECTIONAL",
        validFromEpochMs = 1L,
        validToEpochMs = validTo,
        temporalPrecision = "DAY",
        evidenceRefsJson = "[]",
        confidence = 1.0,
        verificationState = "USER_CONFIRMED",
        status = "ACTIVE",
        recordedAtEpochMs = 1L,
        updatedAtEpochMs = validTo ?: 1L,
    )
}
