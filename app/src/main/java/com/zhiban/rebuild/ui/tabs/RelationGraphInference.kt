package com.zhiban.rebuild.ui.tabs

import com.zhiban.rebuild.data.contact.ContactEntity
import com.zhiban.rebuild.data.contact.RelationshipEdgeEntity
import com.zhiban.rebuild.data.contact.RelationshipEpisodeEntity
import com.zhiban.rebuild.data.contact.RelationshipPersonIds
import com.zhiban.rebuild.data.facts.FactEntity
import com.zhiban.rebuild.relationship.HistoricalRelationshipVisibility
import com.zhiban.rebuild.relationship.RelationshipGroup
import com.zhiban.rebuild.relationship.RelationshipTaxonomy

internal const val INFERRED_COMPANY_RELATIONSHIP_STATUS = "INFERRED_COMPANY"
internal const val INFERRED_HISTORICAL_COMPANY_RELATIONSHIP_STATUS = "INFERRED_HISTORICAL_COMPANY"
internal const val INFERRED_HISTORICAL_COMPANY_UNKNOWN_TIME_STATUS = "INFERRED_HISTORICAL_COMPANY_UNKNOWN_TIME"
internal const val INFERRED_COMPANY_UNKNOWN_TIME_STATUS = "INFERRED_COMPANY_UNKNOWN_TIME"
internal const val INTERACTION_EVIDENCE_STATUS = "INTERACTION_EVIDENCE"

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

internal fun RelationshipEdgeEntity.isInferredEvidenceRelationship(): Boolean = status in setOf(
    INFERRED_COMPANY_RELATIONSHIP_STATUS,
    INFERRED_HISTORICAL_COMPANY_RELATIONSHIP_STATUS,
    INFERRED_HISTORICAL_COMPANY_UNKNOWN_TIME_STATUS,
    INFERRED_COMPANY_UNKNOWN_TIME_STATUS,
    INTERACTION_EVIDENCE_STATUS,
)

internal fun RelationshipEdgeEntity.inferredEvidenceLabel(): String? = when (status) {
    INFERRED_COMPANY_RELATIONSHIP_STATUS -> "同公司推测"
    INFERRED_HISTORICAL_COMPANY_RELATIONSHIP_STATUS -> "曾在同公司任职"
    INFERRED_HISTORICAL_COMPANY_UNKNOWN_TIME_STATUS -> "曾在同公司 · 时间待核实"
    INFERRED_COMPANY_UNKNOWN_TIME_STATUS -> "同公司 · 时间待核实"
    INTERACTION_EVIDENCE_STATUS -> "有联系 · 来自消息互动"
    else -> null
}

/**
 * ① 互动边:从互动摘要事实投影 SELF↔联系人 的只读边(不落库、不可点开编辑),
 * 让图谱显示"哪些人有真实联系"。边强度信息放在 evidenceDigest 里。
 */
internal fun interactionEvidenceEdges(
    interactions: List<FactEntity>,
    contacts: List<ContactEntity>,
    ownerContactSources: List<ContactEntity>,
): List<RelationshipEdgeEntity> {
    val visibleIds = contacts.mapTo(hashSetOf(), ContactEntity::contactId)
    val ownerIds = ownerContactSources.mapTo(hashSetOf(), ContactEntity::contactId)
    val now = System.currentTimeMillis()
    return interactions
        .filter { it.contactId != null && it.contactId !in ownerIds && it.contactId in visibleIds }
        .groupBy(FactEntity::contactId)
        .mapNotNull { (contactId, facts) ->
            val contactId = contactId ?: return@mapNotNull null
            val latest = facts.maxOfOrNull(FactEntity::createdAtEpochMs) ?: now
            val recencyDays = ((now - latest) / (24 * 60 * 60 * 1_000L)).coerceAtLeast(0)
            RelationshipEdgeEntity(
                edgeId = "interaction-user:self-$contactId",
                fromContactId = RelationshipPersonIds.SELF,
                toContactId = contactId,
                relationType = "INTERACTION",
                evidenceDigest = "近 90 天互动 ${facts.size} 次 · 最近 $recencyDays 天前",
                evidenceRefsJson = facts.map { it.factId }.take(5).toString(),
                confidence = 1.0,
                userConfirmed = false,
                skillId = null,
                status = INTERACTION_EVIDENCE_STATUS,
                createdAtEpochMs = latest,
                updatedAtEpochMs = latest,
            )
        }
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

internal fun RelationshipEdgeEntity.isHistoricalRelationship(): Boolean = status == "HISTORICAL" ||
    status == INFERRED_HISTORICAL_COMPANY_RELATIONSHIP_STATUS ||
    status == INFERRED_HISTORICAL_COMPANY_UNKNOWN_TIME_STATUS

internal fun RelationshipEdgeEntity.displayRelationLabel(): String = when (status) {
    INFERRED_HISTORICAL_COMPANY_UNKNOWN_TIME_STATUS -> "可能是前同事"
    INFERRED_COMPANY_UNKNOWN_TIME_STATUS -> "同公司"
    INTERACTION_EVIDENCE_STATUS -> "有联系"
    else -> relationLabel(relationType, isHistorical = isHistoricalRelationship())
}

private fun relationshipPairKey(firstId: String, secondId: String): String = listOf(firstId, secondId).sorted().joinToString("::")

private fun relationshipIdentityKey(edge: RelationshipEdgeEntity): String = "${relationshipPairKey(edge.fromContactId, edge.toContactId)}::${edge.relationType}"
