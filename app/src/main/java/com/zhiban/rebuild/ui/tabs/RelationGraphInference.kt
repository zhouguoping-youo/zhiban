package com.zhiban.rebuild.ui.tabs

import com.zhiban.rebuild.data.contact.ContactEntity
import com.zhiban.rebuild.data.contact.RelationshipEdgeEntity
import com.zhiban.rebuild.data.contact.RelationshipPersonIds
import com.zhiban.rebuild.data.contact.corporateEmailDomain

internal const val INFERRED_COMPANY_RELATIONSHIP_STATUS = "INFERRED_COMPANY"
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
): List<RelationshipEdgeEntity> {
    val companiesByPerson = companyEvidenceByPerson(contacts, ownerContactSources)
    val emailDomainsByPerson = emailDomainEvidenceByPerson(contacts, ownerContactSources)
    if (companiesByPerson.size < 2 && emailDomainsByPerson.size < 2) return savedEdges

    val savedColleaguePairs = savedEdges.asSequence()
        .filter { it.relationType == "COLLEAGUE" }
        .map { relationshipPairKey(it.fromContactId, it.toContactId) }
        .toMutableSet()
    val companyEdges = inferEvidencePairs(
        evidenceByPerson = companiesByPerson.mapValues { (_, values) -> values.associate { it.key to it.displayName } },
        status = INFERRED_COMPANY_RELATIONSHIP_STATUS,
        evidencePrefix = "联系人资料中的公司名称一致",
        evidenceRef = "contact.company",
        confidence = 0.88,
        blockedPairs = savedColleaguePairs,
    )
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

private fun companyEvidenceByPerson(contacts: List<ContactEntity>, ownerContactSources: List<ContactEntity>): Map<String, Set<NormalizedCompany>> = buildMap {
    contacts.forEach { contact -> contact.company?.toNormalizedCompany()?.let { put(contact.contactId, setOf(it)) } }
    ownerContactSources.mapNotNull { it.company?.toNormalizedCompany() }.toSet()
        .takeIf { it.isNotEmpty() }
        ?.let { put(RelationshipPersonIds.SELF, it) }
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
    val people = evidenceByPerson.keys.sorted()
    people.forEachIndexed { index, firstId ->
        people.drop(index + 1).forEach { secondId ->
            val sharedKey = evidenceByPerson.getValue(firstId).keys
                .firstOrNull(evidenceByPerson.getValue(secondId)::containsKey)
                ?: return@forEach
            val pairKey = relationshipPairKey(firstId, secondId)
            if (pairKey in blockedPairs) return@forEach
            val displayValue = evidenceByPerson.getValue(firstId).getValue(sharedKey)
            add(inferredColleagueEdge(firstId, secondId, pairKey, status, "$evidencePrefix：$displayValue", evidenceRef, confidence))
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

internal fun RelationshipEdgeEntity.isInferredCompanyRelationship(): Boolean = status == INFERRED_COMPANY_RELATIONSHIP_STATUS

internal fun RelationshipEdgeEntity.isInferredEvidenceRelationship(): Boolean =
    status == INFERRED_COMPANY_RELATIONSHIP_STATUS || status == INFERRED_EMAIL_DOMAIN_RELATIONSHIP_STATUS

internal fun RelationshipEdgeEntity.inferredEvidenceLabel(): String? = when (status) {
    INFERRED_COMPANY_RELATIONSHIP_STATUS -> "同公司推测"
    INFERRED_EMAIL_DOMAIN_RELATIONSHIP_STATUS -> "企业邮箱推测"
    else -> null
}

private data class NormalizedCompany(val key: String, val displayName: String)

private fun String.toNormalizedCompany(): NormalizedCompany? {
    val display = trim().replace(Regex("\\s+"), " ")
    val key = display.lowercase().replace(" ", "")
    return key.takeIf { it.length >= 4 }?.let { NormalizedCompany(it, display) }
}

private fun relationshipPairKey(firstId: String, secondId: String): String = listOf(firstId, secondId).sorted().joinToString("::")
