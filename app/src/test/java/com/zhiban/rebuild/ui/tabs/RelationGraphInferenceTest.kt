package com.zhiban.rebuild.ui.tabs

import com.zhiban.rebuild.data.contact.ContactEntity
import com.zhiban.rebuild.data.contact.RelationshipPersonIds
import com.zhiban.rebuild.data.contact.RelationshipEventEntity
import com.zhiban.rebuild.data.contact.RelationshipEventParticipantEntity
import com.zhiban.rebuild.data.contact.RelationshipEventWithParticipants
import com.zhiban.rebuild.data.contact.RelationshipEdgeEntity
import com.zhiban.rebuild.data.facts.FactEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RelationGraphInferenceTest {
    private fun contact(id: String, name: String) = ContactEntity(
        contactId = id,
        displayName = name,
        normalizedName = name.lowercase(),
        phone = null,
        email = null,
        wechatId = null,
        company = null,
        title = null,
        aliasesJson = "[]",
        tagsJson = "[]",
        note = null,
        avatarUri = null,
        source = "MANUAL",
        deletedAtEpochMs = null,
        createdAtEpochMs = 1_000L,
        updatedAtEpochMs = 1_000L,
    )

    private fun interaction(contactId: String, factId: String, createdAtEpochMs: Long) = FactEntity(
        factId = factId,
        factType = "INTERACTION_SUMMARY",
        textContent = "微信互动 · $contactId",
        structuredDataJson = null,
        sourceType = "OBSERVED_NOTIFICATION",
        sourceRef = "source",
        contactId = contactId,
        skillId = null,
        confidence = 1.0,
        sensitivity = "PERSONAL",
        status = "ACTIVE",
        ttlDays = 90,
        expiresAtEpochMs = null,
        createdAtEpochMs = createdAtEpochMs,
        updatedAtEpochMs = createdAtEpochMs,
    )

    @Test
    fun `interactions project one evidence edge per contacted person`() {
        val contacts = listOf(contact("a", "张三"), contact("b", "李四"))
        val edges = interactionEvidenceEdges(
            interactions = listOf(
                interaction("a", "f1", System.currentTimeMillis()),
                interaction("a", "f2", System.currentTimeMillis()),
                interaction("b", "f3", System.currentTimeMillis()),
            ),
            contacts = contacts,
            ownerContactSources = emptyList(),
        )

        assertEquals(2, edges.size)
        assertTrue(edges.all { it.fromContactId == RelationshipPersonIds.SELF })
        assertEquals("a", edges[0].toContactId)
        assertTrue(edges[0].evidenceDigest.contains("2 次"))
        assertTrue(edges.all { it.status == INTERACTION_EVIDENCE_STATUS })
        assertTrue(edges.all { it.isInferredEvidenceRelationship() })
        assertEquals("有联系", edges[0].displayRelationLabel())
    }

    @Test
    fun `owner sources and unknown contacts are excluded from interaction edges`() {
        val contacts = listOf(contact("a", "张三"))
        val owner = contact("owner", "本人")
        val edges = interactionEvidenceEdges(
            interactions = listOf(
                interaction("a", "f1", System.currentTimeMillis()),
                interaction("owner", "f2", System.currentTimeMillis()),
                interaction("missing", "f3", System.currentTimeMillis()),
            ),
            contacts = contacts,
            ownerContactSources = listOf(owner),
        )

        assertEquals(1, edges.size)
        assertEquals("a", edges[0].toContactId)
    }

    @Test
    fun `owner graph does not pretend unrelated contact edges are connected to self`() {
        val contactEdges = listOf(
            com.zhiban.rebuild.data.contact.RelationshipEdgeEntity(
                edgeId = "e1",
                fromContactId = "a",
                toContactId = "b",
                relationType = "COLLEAGUE",
                evidenceDigest = "x",
                evidenceRefsJson = "[]",
                confidence = 1.0,
                userConfirmed = true,
                skillId = null,
                status = "ACTIVE",
                createdAtEpochMs = 1L,
                updatedAtEpochMs = 1L,
            ),
        )

        assertTrue(relationshipGraphEdgesForRoot(RelationshipPersonIds.SELF, contactEdges).isEmpty())
        assertEquals(contactEdges, relationshipGraphEdgesForRoot("a", contactEdges))
    }

    @Test
    fun `work category matches persisted colleague edges`() {
        val li = contact("li", "李应啸")
        val colleagueEdge = com.zhiban.rebuild.data.contact.RelationshipEdgeEntity(
            edgeId = "auto-edge-user:self-li-COLLEAGUE",
            fromContactId = RelationshipPersonIds.SELF,
            toContactId = "li",
            relationType = "COLLEAGUE",
            evidenceDigest = "与你是同公司",
            evidenceRefsJson = "[]",
            confidence = 0.95,
            userConfirmed = false,
            skillId = null,
            status = "ACTIVE",
            createdAtEpochMs = 1L,
            updatedAtEpochMs = 1L,
        )

        assertTrue(contactMatchesRelationCategory(li, "工作", listOf(colleagueEdge)))
        assertTrue(contactMatchesRelationCategory(li, "家人", listOf(colleagueEdge)).not())
    }

    @Test
    fun `profile company suppresses owner employment completion prompt`() {
        assertTrue(shouldShowOwnerEmploymentAnchor("", null))
        assertTrue(shouldShowOwnerEmploymentAnchor("平凯星辰（北京）科技有限公司", null).not())
    }

    @Test
    fun `introduction event projects owner subject and introducer edges`() {
        val subject = contact("huang", "黄勇")
        val introducer = contact("ding", "丁波")
        val event = RelationshipEventWithParticipants(
            event = RelationshipEventEntity(
                eventId = "event-1",
                eventType = "INTRODUCTION",
                title = "丁波介绍我认识黄勇",
                note = null,
                occurredAtEpochMs = 1_000L,
                evidenceDigest = "user-confirmed",
                evidenceRefsJson = "[]",
                userConfirmed = true,
                status = "ACTIVE",
                createdAtEpochMs = 1_000L,
                updatedAtEpochMs = 1_000L,
            ),
            participants = listOf(
                RelationshipEventParticipantEntity("p-user", "event-1", "USER", null, "RECIPIENT", "我", 1_000L),
                RelationshipEventParticipantEntity("p-subject", "event-1", "CONTACT", "huang", "SUBJECT", "黄勇", 1_000L),
                RelationshipEventParticipantEntity("p-introducer", "event-1", "CONTACT", "ding", "INTRODUCER", "丁波", 1_000L),
            ),
        )

        val edges = relationshipEventEdges(listOf(event), listOf(subject, introducer))

        assertEquals(3, edges.size)
        assertEquals("介绍认识", edges.first { it.toContactId == "huang" }.displayRelationLabel())
        assertEquals("介绍人", edges.first { it.toContactId == "ding" }.displayRelationLabel())
        assertTrue(edges.all { it.status == INTRODUCTION_EVENT_STATUS })
        assertTrue(edges.all(RelationshipEdgeEntity::isInferredEvidenceRelationship))
        assertTrue(edges.any { it.fromContactId == "huang" && it.toContactId == "ding" })
    }

    @Test
    fun `duplicate visible relationship rows collapse and confirmed edge wins`() {
        val inferred = RelationshipEdgeEntity(
            edgeId = "inferred",
            fromContactId = RelationshipPersonIds.SELF,
            toContactId = "ding",
            relationType = "COLLEAGUE",
            evidenceDigest = "来自推断",
            evidenceRefsJson = "[]",
            confidence = 0.99,
            userConfirmed = false,
            skillId = null,
            status = "INFERRED_COMPANY",
            createdAtEpochMs = 2L,
            updatedAtEpochMs = 2L,
        )
        val confirmed = inferred.copy(
            edgeId = "confirmed",
            evidenceDigest = "用户确认",
            userConfirmed = true,
            status = "ACTIVE",
            updatedAtEpochMs = 1L,
        )

        val visible = mergeCurrentAndHistoricalRelationships(
            current = listOf(inferred, confirmed),
            historical = emptyList(),
        )

        assertEquals(1, visible.size)
        assertEquals("confirmed", visible.single().edgeId)
    }
}
