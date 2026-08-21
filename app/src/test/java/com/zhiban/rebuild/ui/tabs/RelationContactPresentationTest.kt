package com.zhiban.rebuild.ui.tabs

import com.zhiban.rebuild.data.contact.ContactEntity
import com.zhiban.rebuild.data.contact.RelationshipEdgeEntity
import com.zhiban.rebuild.data.contact.RelationshipPersonIds
import com.zhiban.rebuild.data.interaction.ContactInteractionIntensity
import org.junit.Assert.assertEquals
import org.junit.Test

class RelationContactPresentationTest {
    @Test
    fun `contact summary leads with relationship then company and recency`() {
        val now = 20L * DAY
        val summary = contactContextSummary(
            contact = contact("ding", "丁波", "平凯星辰（北京）科技有限公司"),
            relationships = listOf(edge(RelationshipPersonIds.SELF, "ding", "COLLEAGUE")),
            interaction = ContactInteractionIntensity("ding", 3, now - 6L * DAY),
            nowEpochMs = now,
        )

        assertEquals("前同事 · 平凯星辰（北京）科技有限公司 · 6天前联系", summary)
    }

    @Test
    fun `future or absent interaction does not create misleading recency`() {
        assertEquals(null, relationshipInteractionRecency(0L, 10L))
        assertEquals(null, relationshipInteractionRecency(11L, 10L))
    }

    private fun contact(id: String, name: String, company: String) = ContactEntity(
        contactId = id,
        displayName = name,
        normalizedName = name,
        phone = null,
        email = null,
        wechatId = null,
        company = company,
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

    private fun edge(from: String, to: String, type: String) = RelationshipEdgeEntity(
        edgeId = "$from-$to",
        fromContactId = from,
        toContactId = to,
        relationType = type,
        evidenceDigest = "test",
        evidenceRefsJson = "[]",
        confidence = 1.0,
        userConfirmed = true,
        skillId = null,
        status = "HISTORICAL",
        createdAtEpochMs = 1L,
        updatedAtEpochMs = 1L,
    )

    private companion object {
        const val DAY = 86_400_000L
    }
}
