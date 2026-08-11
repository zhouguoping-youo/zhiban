package com.zhiban.rebuild.data.agent

import com.zhiban.rebuild.data.contact.PersonEntity
import com.zhiban.rebuild.data.contact.RelationshipEpisodeEntity
import com.zhiban.rebuild.data.contact.RelationshipPersonIds

/**
 * Writes one relationship episode without collapsing other relationship types between the same
 * people. A replacement closes only the matching type, so someone can remain both a friend and a
 * former colleague.
 */
internal class TemporalRelationshipWriter(private val database: AgentDatabase) {
    suspend fun replaceEpisode(
        episodeKey: String,
        fromPersonId: String,
        toPersonId: String,
        relationshipType: String,
        temporalState: String,
        evidenceRefsJson: String,
        confidence: Double,
        verificationState: String,
        nowEpochMs: Long,
    ): RelationshipEpisodeEntity {
        require(temporalState in setOf("CURRENT", "PAST", "UNKNOWN"))
        ensurePerson(fromPersonId, nowEpochMs)
        ensurePerson(toPersonId, nowEpochMs)
        database.contactIntelligenceDao().closeOpenUserRelationships(
            fromPersonId,
            toPersonId,
            relationshipType,
            nowEpochMs,
        )
        return RelationshipEpisodeEntity(
            episodeId = stableContactKnowledgeId(
                "relationship-episode",
                episodeKey,
                temporalState,
                nowEpochMs.toString(),
            ),
            fromPersonId = fromPersonId,
            toPersonId = toPersonId,
            relationshipType = relationshipType,
            direction = "BIDIRECTIONAL",
            validFromEpochMs = nowEpochMs.takeIf { temporalState == "CURRENT" },
            validToEpochMs = nowEpochMs.takeIf { temporalState == "PAST" },
            temporalPrecision = when (temporalState) {
                "UNKNOWN" -> "UNKNOWN"
                "PAST" -> "END_ONLY"
                else -> "OPEN"
            },
            evidenceRefsJson = evidenceRefsJson,
            confidence = confidence,
            verificationState = verificationState,
            status = "ACTIVE",
            recordedAtEpochMs = nowEpochMs,
            updatedAtEpochMs = nowEpochMs,
        ).also { database.contactIntelligenceDao().upsertRelationship(it) }
    }

    private suspend fun ensurePerson(personId: String, nowEpochMs: Long) {
        val intelligence = database.contactIntelligenceDao()
        if (intelligence.findPerson(personId) != null) return
        val contact = personId.takeUnless { it == RelationshipPersonIds.SELF }
            ?.let { database.contactDao().findById(it) }
        intelligence.upsertPerson(
            PersonEntity(
                personId = personId,
                canonicalContactId = contact?.contactId,
                displayName = contact?.displayName ?: "我",
                normalizedName = contact?.normalizedName ?: "self",
                kind = if (contact == null) "USER" else "CONTACT",
                status = "ACTIVE",
                createdAtEpochMs = contact?.createdAtEpochMs ?: nowEpochMs,
                updatedAtEpochMs = nowEpochMs,
            ),
        )
    }
}
