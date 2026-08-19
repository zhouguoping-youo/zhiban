package com.zhiban.rebuild.ui.tabs

import com.zhiban.rebuild.data.contact.ContactEntity
import com.zhiban.rebuild.data.contact.RelationshipPersonIds
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
}
