package com.zhiban.rebuild.ui.tabs

import com.zhiban.rebuild.data.contact.ContactEntity
import com.zhiban.rebuild.data.contact.PersonEmploymentEpisodeEntity
import com.zhiban.rebuild.data.contact.RelationshipEdgeEntity
import com.zhiban.rebuild.data.contact.RelationshipEpisodeEntity
import com.zhiban.rebuild.data.contact.RelationshipPersonIds
import com.zhiban.rebuild.data.contact.corporateEmailDomain

internal const val INFERRED_COMPANY_RELATIONSHIP_STATUS = "INFERRED_COMPANY"
internal const val INFERRED_COMPANY_UNKNOWN_TIME_STATUS = "INFERRED_COMPANY_UNKNOWN_TIME"
internal const val INFERRED_EMAIL_DOMAIN_RELATIONSHIP_STATUS = "INFERRED_EMAIL_DOMAIN"

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
    val employmentEvidence = employmentEvidenceByPerson(employmentEpisodes, ownerIds)
    val emailDomainsByPerson = emailDomainEvidenceByPerson(contacts, ownerContactSources)
    if (employmentEvidence.size < 2 && emailDomainsByPerson.size < 2) return savedEdges

    val savedColleaguePairs = savedEdges.asSequence()
        .filter { it.relationType == "COLLEAGUE" }
        .map { relationshipPairKey(it.fromContactId, it.toContactId) }
        .toMutableSet()
    val companyEdges = inferTemporalCompanyPairs(employmentEvidence, savedColleaguePairs)
    savedColleaguePairs += companyEdges.map { relationshipPairKey(it.fromContactId, it.toContactId) }
    val emailEdges = inferEvidencePairs(
        evidenceByPerson = emailDomainsByPerson.mapValues { (_, values) -> values.associateWith { it } },
        status = INFERRED_EMAIL_DOMAIN_RELATIONSHIP_STATUS,
        evidencePrefix = "企业邮箱域一致",
        evidenceRef = "contact.email.domain",
        confidence = 0.8,
        blockedPairs = savedColleaguePairs,
    )
    return savedEdges + companyEdges + emailEdges
}

private fun employmentEvidenceByPerson(episodes: List<PersonEmploymentEpisodeEntity>, ownerContactIds: Set<String>): Map<String, List<EmploymentEvidence>> =
    episodes.filter { it.status == "ACTIVE" }
        .mapNotNull { episode ->
            val company = episode.companyNameSnapshot.toNormalizedCompany() ?: return@mapNotNull null
            val personId = if (episode.personId in ownerContactIds) RelationshipPersonIds.SELF else episode.personId
            personId to EmploymentEvidence(company, episode.validFromEpochMs, episode.validToEpochMs)
        }.groupBy({ it.first }, { it.second })

private fun inferTemporalCompanyPairs(evidenceByPerson: Map<String, List<EmploymentEvidence>>, blockedPairs: Set<String>): List<RelationshipEdgeEntity> =
    buildList {
        val emittedPairs = blockedPairs.toMutableSet()
        val peopleByCompany = evidenceByPerson.flatMap { (personId, episodes) ->
            episodes.map { it.company.key to personId }
        }.groupBy({ it.first }, { it.second })
        peopleByCompany.forEach { (companyKey, people) ->
            val distinctPeople = people.distinct().sorted()
            if (distinctPeople.size < 2) return@forEach
            val pairs = if (RelationshipPersonIds.SELF in distinctPeople) {
                distinctPeople.filterNot { it == RelationshipPersonIds.SELF }.map { RelationshipPersonIds.SELF to it }
            } else {
                distinctPeople.zipWithNext()
            }
            pairs.forEach { (firstId, secondId) ->
                val pairKey = relationshipPairKey(firstId, secondId)
                if (pairKey in emittedPairs) return@forEach
                val firstEpisodes = evidenceByPerson.getValue(firstId).filter { it.company.key == companyKey }
                val secondEpisodes = evidenceByPerson.getValue(secondId).filter { it.company.key == companyKey }
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
    if (knownPairs.any { (a, b) -> a.overlaps(b) }) {
        return TemporalCompanyMatch(INFERRED_COMPANY_RELATIONSHIP_STATUS, "任职时间重叠", 0.9)
    }
    if (allPairs.isNotEmpty() && allPairs.size == knownPairs.size) return null
    return TemporalCompanyMatch(INFERRED_COMPANY_UNKNOWN_TIME_STATUS, "同公司，时间待核实", 0.65)
}

private fun emailDomainEvidenceByPerson(contacts: List<ContactEntity>, ownerContactSources: List<ContactEntity>): Map<String, Set<String>> = buildMap {
    contacts.forEach { contact -> corporateEmailDomain(contact.email)?.let { put(contact.contactId, setOf(it)) } }
    ownerContactSources.mapNotNull { corporateEmailDomain(it.email) }.toSet()
        .takeIf { it.isNotEmpty() }
        ?.let { put(RelationshipPersonIds.SELF, it) }
}

private fun inferEvidencePairs(
    evidenceByPerson: Map<String, Map<String, String>>,
    status: String,
    evidencePrefix: String,
    evidenceRef: String,
    confidence: Double,
    blockedPairs: Set<String>,
): List<RelationshipEdgeEntity> = buildList {
    val emittedPairs = blockedPairs.toMutableSet()
    val peopleByEvidence = buildMap<String, MutableList<String>> {
        evidenceByPerson.forEach { (personId, evidence) ->
            evidence.keys.forEach { key -> getOrPut(key, ::mutableListOf).add(personId) }
        }
    }
    peopleByEvidence.toSortedMap().forEach evidenceGroup@{ (evidenceKey, rawPeople) ->
        val people = rawPeople.distinct().sorted()
        if (people.size < 2) return@evidenceGroup
        val pairs = if (RelationshipPersonIds.SELF in people) {
            people.asSequence()
                .filterNot { it == RelationshipPersonIds.SELF }
                .map { RelationshipPersonIds.SELF to it }
        } else {
            people.zipWithNext().asSequence()
        }
        pairs.forEach pair@{ (firstId, secondId) ->
            val pairKey = relationshipPairKey(firstId, secondId)
            if (!emittedPairs.add(pairKey)) return@pair
            val displayValue = evidenceByPerson.getValue(firstId)[evidenceKey]
                ?: evidenceByPerson.getValue(secondId).getValue(evidenceKey)
            add(
                inferredColleagueEdge(
                    firstId,
                    secondId,
                    pairKey,
                    status,
                    "$evidencePrefix：$displayValue",
                    evidenceRef,
                    confidence,
                ),
            )
        }
    }
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
    if (contact.tagsJson.contains(category, ignoreCase = true)) return true
    val relationTypes = relationships.asSequence()
        .filter { it.fromContactId == contact.contactId || it.toContactId == contact.contactId }
        .map(RelationshipEdgeEntity::relationType)
        .toSet()
    val acceptedTypes = when (category) {
        "工作" -> setOf("COLLEAGUE", "CUSTOMER", "SUPPLIER", "PROJECT_PARTNER")
        "家人" -> setOf("FAMILY")
        "朋友" -> setOf("FRIEND", "CLASSMATE")
        "客户" -> setOf("CUSTOMER")
        else -> emptySet()
    }
    return relationTypes.any(acceptedTypes::contains)
}

internal fun RelationshipEdgeEntity.isInferredCompanyRelationship(): Boolean =
    status == INFERRED_COMPANY_RELATIONSHIP_STATUS || status == INFERRED_COMPANY_UNKNOWN_TIME_STATUS

internal fun RelationshipEdgeEntity.isInferredEvidenceRelationship(): Boolean = status in setOf(
    INFERRED_COMPANY_RELATIONSHIP_STATUS,
    INFERRED_COMPANY_UNKNOWN_TIME_STATUS,
    INFERRED_EMAIL_DOMAIN_RELATIONSHIP_STATUS,
)

internal fun RelationshipEdgeEntity.inferredEvidenceLabel(): String? = when (status) {
    INFERRED_COMPANY_RELATIONSHIP_STATUS -> "同公司推测"
    INFERRED_COMPANY_UNKNOWN_TIME_STATUS -> "同公司 · 时间待核实"
    INFERRED_EMAIL_DOMAIN_RELATIONSHIP_STATUS -> "企业邮箱推测"
    else -> null
}

/** Projects closed temporal episodes into a read-only graph layer. */
internal fun historicalRelationshipEdges(episodes: List<RelationshipEpisodeEntity>): List<RelationshipEdgeEntity> = episodes.asSequence()
    .filter { it.status == "ACTIVE" && it.validToEpochMs != null }
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

internal fun relationshipGraphEdgesForRoot(rootId: String, edges: List<RelationshipEdgeEntity>): List<RelationshipEdgeEntity> {
    val incident = edges.filter { it.fromContactId == rootId || it.toContactId == rootId }
    return if (rootId == RelationshipPersonIds.SELF && incident.isEmpty()) edges else incident
}

private data class NormalizedCompany(val key: String, val displayName: String)

private data class EmploymentEvidence(val company: NormalizedCompany, val validFromEpochMs: Long?, val validToEpochMs: Long?) {
    val hasKnownInterval: Boolean get() = validFromEpochMs != null || validToEpochMs != null

    fun overlaps(other: EmploymentEvidence): Boolean {
        val start = maxOf(validFromEpochMs ?: Long.MIN_VALUE, other.validFromEpochMs ?: Long.MIN_VALUE)
        val end = minOf(validToEpochMs ?: Long.MAX_VALUE, other.validToEpochMs ?: Long.MAX_VALUE)
        return start <= end
    }
}

private data class TemporalCompanyMatch(val status: String, val label: String, val confidence: Double)

private fun String.toNormalizedCompany(): NormalizedCompany? {
    val display = trim().replace(Regex("\\s+"), " ")
    val key = display.lowercase().replace(" ", "")
    return key.takeIf { it.length >= 4 }?.let { NormalizedCompany(it, display) }
}

private fun relationshipPairKey(firstId: String, secondId: String): String = listOf(firstId, secondId).sorted().joinToString("::")
