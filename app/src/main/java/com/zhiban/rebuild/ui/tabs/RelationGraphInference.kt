package com.zhiban.rebuild.ui.tabs

import com.zhiban.rebuild.data.contact.ContactEntity
import com.zhiban.rebuild.data.contact.PersonEmploymentEpisodeEntity
import com.zhiban.rebuild.data.contact.RelationshipEdgeEntity
import com.zhiban.rebuild.data.contact.RelationshipEpisodeEntity
import com.zhiban.rebuild.data.contact.RelationshipPersonIds
import com.zhiban.rebuild.relationship.HistoricalRelationshipVisibility
import com.zhiban.rebuild.relationship.RelationshipGroup
import com.zhiban.rebuild.relationship.RelationshipTaxonomy

internal const val INFERRED_COMPANY_RELATIONSHIP_STATUS = "INFERRED_COMPANY"
internal const val INFERRED_HISTORICAL_COMPANY_RELATIONSHIP_STATUS = "INFERRED_HISTORICAL_COMPANY"
internal const val INFERRED_HISTORICAL_COMPANY_UNKNOWN_TIME_STATUS = "INFERRED_HISTORICAL_COMPANY_UNKNOWN_TIME"
internal const val INFERRED_COMPANY_UNKNOWN_TIME_STATUS = "INFERRED_COMPANY_UNKNOWN_TIME"

/**
 * Adds reversible, display-only colleague links when two people have the same explicit company.
 *
 * These edges are deliberately not written to Room: a company match is useful graph evidence, but
 * it is still an inference rather than a user-confirmed fact. A saved colleague edge always wins.
 */
internal fun withInferredCompanyRelationships(
    contacts: List<ContactEntity>,
    ownerContactSources: List<ContactEntity>,
    savedEdges: List<RelationshipEdgeEntity>,
    employmentEpisodes: List<PersonEmploymentEpisodeEntity> = emptyList(),
): List<RelationshipEdgeEntity> {
    val ownerIds = ownerContactSources.mapTo(hashSetOf(), ContactEntity::contactId)
    val visiblePersonIds = contacts.mapTo(hashSetOf(), ContactEntity::contactId).apply {
        addAll(ownerIds)
        add(RelationshipPersonIds.SELF)
    }
    val employmentEvidence = employmentEvidenceByPerson(employmentEpisodes, ownerIds, visiblePersonIds)
    if (employmentEvidence.size < 2) return savedEdges

    val savedColleaguePairs = savedEdges.asSequence()
        .filter { it.relationType == "COLLEAGUE" }
        .map { relationshipPairKey(it.fromContactId, it.toContactId) }
        .toMutableSet()
    val companyEdges = inferTemporalCompanyPairs(employmentEvidence, savedColleaguePairs)
    return savedEdges + companyEdges
}

private fun employmentEvidenceByPerson(
    episodes: List<PersonEmploymentEpisodeEntity>,
    ownerContactIds: Set<String>,
    visiblePersonIds: Set<String>,
): Map<String, List<EmploymentEvidence>> = episodes
    .filter { it.status == "ACTIVE" && it.personId in visiblePersonIds }
    .filter { episode ->
        val belongsToOwner = episode.personId == RelationshipPersonIds.SELF || episode.personId in ownerContactIds
        !belongsToOwner || episode.verificationState == "USER_CONFIRMED"
    }
    .mapNotNull { episode ->
        val company = episode.companyNameSnapshot.toNormalizedCompany() ?: return@mapNotNull null
        val personId = if (episode.personId in ownerContactIds) RelationshipPersonIds.SELF else episode.personId
        personId to EmploymentEvidence(
            company,
            episode.organizationId,
            episode.validFromEpochMs,
            episode.validToEpochMs,
            episode.currentState,
        )
    }.groupBy({ it.first }, { it.second })

private fun inferTemporalCompanyPairs(evidenceByPerson: Map<String, List<EmploymentEvidence>>, blockedPairs: Set<String>): List<RelationshipEdgeEntity> =
    buildList {
        val emittedPairs = blockedPairs.toMutableSet()
        val peopleByCompany = evidenceByPerson.flatMap { (personId, episodes) ->
            episodes.map { it.groupingKey to personId }
        }.groupBy({ it.first }, { it.second })
        peopleByCompany.forEach { (companyKey, people) ->
            val distinctPeople = people.distinct().sorted()
            if (distinctPeople.size < 2 || RelationshipPersonIds.SELF !in distinctPeople) return@forEach
            val pairs = distinctPeople.filterNot { it == RelationshipPersonIds.SELF }
                .map { RelationshipPersonIds.SELF to it }
            pairs.forEach { (firstId, secondId) ->
                val pairKey = relationshipPairKey(firstId, secondId)
                if (pairKey in emittedPairs) return@forEach
                val firstEpisodes = evidenceByPerson.getValue(firstId).filter { it.groupingKey == companyKey }
                val secondEpisodes = evidenceByPerson.getValue(secondId).filter { it.groupingKey == companyKey }
                val timing = temporalMatch(firstEpisodes, secondEpisodes) ?: return@forEach
                emittedPairs += pairKey
                val company = (firstEpisodes + secondEpisodes).first().company.displayName
                add(
                    inferredColleagueEdge(
                        firstId,
                        secondId,
                        pairKey,
                        timing.status,
                        "${timing.label}：$company",
                        "employment.episode",
                        timing.confidence,
                    ),
                )
            }
        }
    }

private fun temporalMatch(first: List<EmploymentEvidence>, second: List<EmploymentEvidence>): TemporalCompanyMatch? {
    val allPairs = first.flatMap { a -> second.map { b -> a to b } }
    val knownPairs = allPairs.filter { (a, b) -> a.hasKnownInterval && b.hasKnownInterval }
    val overlapping = knownPairs.firstOrNull { (a, b) -> a.overlaps(b) }
    if (overlapping != null) {
        if (overlapping.first.currentState == "PAST" || overlapping.second.currentState == "PAST") {
            return TemporalCompanyMatch(
                INFERRED_HISTORICAL_COMPANY_RELATIONSHIP_STATUS,
                "曾在同公司任职",
                0.9,
            )
        }
        return TemporalCompanyMatch(INFERRED_COMPANY_RELATIONSHIP_STATUS, "任职时间重叠", 0.9)
    }
    if (allPairs.isNotEmpty() && allPairs.size == knownPairs.size) return null
    if (allPairs.any { (a, b) -> a.currentState == "PAST" || b.currentState == "PAST" }) {
        return TemporalCompanyMatch(
            INFERRED_HISTORICAL_COMPANY_UNKNOWN_TIME_STATUS,
            "曾在同公司 · 时间待核实",
            0.7,
        )
    }
    return TemporalCompanyMatch(INFERRED_COMPANY_UNKNOWN_TIME_STATUS, "同公司，时间待核实", 0.65)
}

private fun inferredColleagueEdge(
    firstId: String,
    secondId: String,
    pairKey: String,
    status: String,
    evidence: String,
    evidenceRef: String,
    confidence: Double,
) = RelationshipEdgeEntity(
    edgeId = "inferred:$status:$pairKey",
    fromContactId = firstId,
    toContactId = secondId,
    relationType = "COLLEAGUE",
    evidenceDigest = evidence.take(180),
    evidenceRefsJson = "[\"$evidenceRef\"]",
    confidence = confidence,
    userConfirmed = false,
    skillId = null,
    status = status,
    createdAtEpochMs = 0L,
    updatedAtEpochMs = 0L,
)

internal fun contactMatchesRelationCategory(contact: ContactEntity, category: String, relationships: List<RelationshipEdgeEntity>): Boolean {
    if (category == "全部") return true
    val group = RelationshipGroup.entries.firstOrNull { it.displayName == category } ?: return false
    val legacyTags = when (group) {
        RelationshipGroup.WORK -> listOf("工作", "客户")
        RelationshipGroup.SOCIAL -> listOf("朋友", "同学")
        else -> listOf(group.displayName)
    }
    if (legacyTags.any { contact.tagsJson.contains(it, ignoreCase = true) }) {
        return true
    }
    val relationTypes = relationships.asSequence()
        .filter { it.fromContactId == contact.contactId || it.toContactId == contact.contactId }
        .map(RelationshipEdgeEntity::relationType)
        .toSet()
    // Legacy imported codes such as SPOUSE remain readable even when they are no longer offered
    // in the editor; hiding a picker option must never orphan existing data from its group.
    val acceptedTypes = RelationshipTaxonomy.definitions
        .filter { it.group == group }
        .mapTo(hashSetOf()) { it.code }
    return relationTypes.any(acceptedTypes::contains)
}

internal fun RelationshipEdgeEntity.isInferredCompanyRelationship(): Boolean = status == INFERRED_COMPANY_RELATIONSHIP_STATUS ||
    status == INFERRED_HISTORICAL_COMPANY_RELATIONSHIP_STATUS ||
    status == INFERRED_HISTORICAL_COMPANY_UNKNOWN_TIME_STATUS ||
    status == INFERRED_COMPANY_UNKNOWN_TIME_STATUS

internal fun RelationshipEdgeEntity.isInferredEvidenceRelationship(): Boolean = status in setOf(
    INFERRED_COMPANY_RELATIONSHIP_STATUS,
    INFERRED_HISTORICAL_COMPANY_RELATIONSHIP_STATUS,
    INFERRED_HISTORICAL_COMPANY_UNKNOWN_TIME_STATUS,
    INFERRED_COMPANY_UNKNOWN_TIME_STATUS,
)

internal fun RelationshipEdgeEntity.inferredEvidenceLabel(): String? = when (status) {
    INFERRED_COMPANY_RELATIONSHIP_STATUS -> "同公司推测"
    INFERRED_HISTORICAL_COMPANY_RELATIONSHIP_STATUS -> "曾在同公司任职"
    INFERRED_HISTORICAL_COMPANY_UNKNOWN_TIME_STATUS -> "曾在同公司 · 时间待核实"
    INFERRED_COMPANY_UNKNOWN_TIME_STATUS -> "同公司 · 时间待核实"
    else -> null
}

/** Projects closed temporal episodes into a read-only graph layer. */
internal fun historicalRelationshipEdges(episodes: List<RelationshipEpisodeEntity>): List<RelationshipEdgeEntity> = episodes.asSequence()
    .filter { it.status == "ACTIVE" && it.validToEpochMs != null }
    .filter {
        RelationshipTaxonomy.find(it.relationshipType)?.historicalVisibility !=
            HistoricalRelationshipVisibility.HIDE_FROM_DEFAULT_GRAPH
    }
    .sortedByDescending { it.validToEpochMs }
    .map { episode ->
        RelationshipEdgeEntity(
            edgeId = "history:${episode.episodeId}",
            fromContactId = episode.fromPersonId,
            toContactId = episode.toPersonId,
            relationType = episode.relationshipType,
            evidenceDigest = "TEMPORAL_HISTORY",
            evidenceRefsJson = episode.evidenceRefsJson,
            confidence = episode.confidence,
            userConfirmed = episode.verificationState == "USER_CONFIRMED",
            skillId = null,
            status = "HISTORICAL",
            createdAtEpochMs = episode.recordedAtEpochMs,
            updatedAtEpochMs = episode.updatedAtEpochMs,
        )
    }
    .distinctBy(RelationshipEdgeEntity::edgeId)
    .toList()

/**
 * Shows current and past relationships together while preventing a current edge and its closed
 * predecessor from being drawn twice between the same people.
 */
internal fun mergeCurrentAndHistoricalRelationships(
    current: List<RelationshipEdgeEntity>,
    historical: List<RelationshipEdgeEntity>,
): List<RelationshipEdgeEntity> {
    val currentKeys = current.mapTo(hashSetOf(), ::relationshipIdentityKey)
    return current + historical.filterNot { relationshipIdentityKey(it) in currentKeys }
}

internal fun relationshipGraphEdgesForRoot(rootId: String, edges: List<RelationshipEdgeEntity>): List<RelationshipEdgeEntity> = edges.filter {
    it.fromContactId == rootId || it.toContactId == rootId
}

private data class NormalizedCompany(val key: String, val displayName: String)

private data class EmploymentEvidence(
    val company: NormalizedCompany,
    val organizationId: String?,
    val validFromEpochMs: Long?,
    val validToEpochMs: Long?,
    val currentState: String,
) {
    // Registry-backed contacts may use a credit-code ID while an older owner record uses a
    // name-derived ID. The confirmed legal name is the shared key until aliases are explicit.
    val groupingKey: String = "name:${company.key}"
    val hasKnownInterval: Boolean get() = validFromEpochMs != null || validToEpochMs != null

    fun overlaps(other: EmploymentEvidence): Boolean {
        val start = maxOf(validFromEpochMs ?: Long.MIN_VALUE, other.validFromEpochMs ?: Long.MIN_VALUE)
        val end = minOf(validToEpochMs ?: Long.MAX_VALUE, other.validToEpochMs ?: Long.MAX_VALUE)
        return start <= end
    }
}

internal fun RelationshipEdgeEntity.isHistoricalRelationship(): Boolean = status == "HISTORICAL" ||
    status == INFERRED_HISTORICAL_COMPANY_RELATIONSHIP_STATUS ||
    status == INFERRED_HISTORICAL_COMPANY_UNKNOWN_TIME_STATUS

internal fun RelationshipEdgeEntity.displayRelationLabel(): String = when (status) {
    INFERRED_HISTORICAL_COMPANY_UNKNOWN_TIME_STATUS -> "可能是前同事"
    INFERRED_COMPANY_UNKNOWN_TIME_STATUS -> "同公司"
    else -> relationLabel(relationType, isHistorical = isHistoricalRelationship())
}

private data class TemporalCompanyMatch(val status: String, val label: String, val confidence: Double)

private fun String.toNormalizedCompany(): NormalizedCompany? {
    val display = trim().replace(Regex("\\s+"), " ")
    val key = display.lowercase().replace(" ", "")
    return key.takeIf { it.length >= 4 }?.let { NormalizedCompany(it, display) }
}

private fun relationshipPairKey(firstId: String, secondId: String): String = listOf(firstId, secondId).sorted().joinToString("::")

private fun relationshipIdentityKey(edge: RelationshipEdgeEntity): String = "${relationshipPairKey(edge.fromContactId, edge.toContactId)}::${edge.relationType}"
