package com.zhiban.rebuild.ui.tabs

import com.zhiban.rebuild.data.contact.ContactEntity
import com.zhiban.rebuild.data.contact.RelationshipEdgeEntity
import com.zhiban.rebuild.data.contact.RelationshipPersonIds

internal const val INFERRED_COMPANY_RELATIONSHIP_STATUS = "INFERRED_COMPANY"

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
    val companiesByPerson = buildMap<String, Set<NormalizedCompany>> {
        contacts.forEach { contact ->
            contact.company?.toNormalizedCompany()?.let { company ->
                put(contact.contactId, setOf(company))
            }
        }
        ownerContactSources.mapNotNull { it.company?.toNormalizedCompany() }
            .toSet()
            .takeIf { it.isNotEmpty() }
            ?.let { put(RelationshipPersonIds.SELF, it) }
    }
    if (companiesByPerson.size < 2) return savedEdges

    val savedColleaguePairs = savedEdges.asSequence()
        .filter { it.relationType == "COLLEAGUE" }
        .map { relationshipPairKey(it.fromContactId, it.toContactId) }
        .toSet()
    val inferred = buildList {
        val people = companiesByPerson.keys.sorted()
        people.forEachIndexed { index, firstId ->
            people.drop(index + 1).forEach { secondId ->
                val secondCompanies = companiesByPerson.getValue(secondId)
                val sharedCompany = companiesByPerson.getValue(firstId)
                    .firstOrNull { first -> secondCompanies.any { second -> first.key == second.key } }
                    ?: return@forEach
                val pairKey = relationshipPairKey(firstId, secondId)
                if (pairKey in savedColleaguePairs) return@forEach
                add(
                    RelationshipEdgeEntity(
                        edgeId = "inferred-company:$pairKey",
                        fromContactId = firstId,
                        toContactId = secondId,
                        relationType = "COLLEAGUE",
                        evidenceDigest = "联系人资料中的公司名称一致：${sharedCompany.displayName.take(100)}",
                        evidenceRefsJson = "[\"contact.company\"]",
                        confidence = 0.88,
                        userConfirmed = false,
                        skillId = null,
                        status = INFERRED_COMPANY_RELATIONSHIP_STATUS,
                        createdAtEpochMs = 0L,
                        updatedAtEpochMs = 0L,
                    ),
                )
            }
        }
    }
    return savedEdges + inferred
}

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

private data class NormalizedCompany(val key: String, val displayName: String)

private fun String.toNormalizedCompany(): NormalizedCompany? {
    val display = trim().replace(Regex("\\s+"), " ")
    val key = display.lowercase().replace(" ", "")
    return key.takeIf { it.length >= 4 }?.let { NormalizedCompany(it, display) }
}

private fun relationshipPairKey(firstId: String, secondId: String): String = listOf(firstId, secondId).sorted().joinToString("::")
