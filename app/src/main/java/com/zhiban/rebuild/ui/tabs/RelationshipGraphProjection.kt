package com.zhiban.rebuild.ui.tabs

import com.zhiban.rebuild.data.contact.RelationshipEdgeEntity
import com.zhiban.rebuild.data.contact.RelationshipPersonIds
import com.zhiban.rebuild.data.interaction.ContactInteractionIntensity

/**
 * A small, UI-independent projection of the relationship graph.
 *
 * The persisted graph is intentionally kept separate from what is drawn. Root view keeps the most
 * relevant direct relationships, while a contact ego view is limited to two hops so that expanding
 * a node never silently turns the canvas into an unreadable all-contact list.
 */
internal data class RelationshipGraphProjection(
    val rootId: String,
    val isEgoView: Boolean,
    val hopByNode: Map<String, Int>,
    val ringByNode: Map<String, RelationshipGraphRing>,
    val edges: List<RelationshipEdgeEntity>,
)

internal enum class RelationshipGraphRing { INNER, MIDDLE, OUTER, UNKNOWN }

internal data class ForceGraphNodePresentation(
    val hopDistance: Int = 0,
    val opacity: Float = 1f,
    val showLabel: Boolean = true,
    val isBackground: Boolean = false,
    val ring: RelationshipGraphRing = RelationshipGraphRing.UNKNOWN,
)

internal fun projectRelationshipGraph(
    rootId: String,
    peopleIds: Set<String>,
    edges: List<RelationshipEdgeEntity>,
    ownerId: String = RelationshipPersonIds.SELF,
    maxEgoHops: Int = 2,
    maxRootNeighbors: Int = 18,
    interactionIntensity: List<ContactInteractionIntensity> = emptyList(),
): RelationshipGraphProjection {
    val validEdges = edges.filter { it.fromContactId in peopleIds && it.toContactId in peopleIds }
    val intensityById = interactionIntensity.associateBy { it.contactId }
    val ringByNode = peopleIds.associateWith { id ->
        relationshipGraphRing(intensityById[id]?.interactionCount ?: 0)
    }
    val adjacency = buildMap<String, MutableSet<String>> {
        validEdges.forEach { edge ->
            getOrPut(edge.fromContactId) { linkedSetOf() }.add(edge.toContactId)
            getOrPut(edge.toContactId) { linkedSetOf() }.add(edge.fromContactId)
        }
    }
    if (rootId == ownerId) {
        return projectOwnerRelationshipGraph(rootId, validEdges, intensityById, ringByNode, maxRootNeighbors)
    }

    val hopByNode = linkedMapOf(rootId to 0)
    var frontier = setOf(rootId)
    repeat(maxEgoHops.coerceAtLeast(0)) { hop ->
        val next = frontier.flatMap { adjacency[it].orEmpty() }
            .filter { it !in hopByNode }
            .toSet()
        next.forEach { hopByNode[it] = hop + 1 }
        frontier = next
        if (frontier.isEmpty()) return@repeat
    }
    val visibleNodes = hopByNode.keys
    return RelationshipGraphProjection(
        rootId = rootId,
        isEgoView = true,
        hopByNode = hopByNode,
        ringByNode = ringByNode,
        edges = validEdges.filter { it.fromContactId in visibleNodes && it.toContactId in visibleNodes },
    )
}

private fun projectOwnerRelationshipGraph(
    rootId: String,
    validEdges: List<RelationshipEdgeEntity>,
    intensityById: Map<String, ContactInteractionIntensity>,
    ringByNode: Map<String, RelationshipGraphRing>,
    maxRootNeighbors: Int,
): RelationshipGraphProjection {
    val strongestEdgeByNeighbor = validEdges
        .filter { it.fromContactId == rootId || it.toContactId == rootId }
        .groupBy { edge -> if (edge.fromContactId == rootId) edge.toContactId else edge.fromContactId }
        .mapValues { (_, values) ->
            values.maxWithOrNull(
                compareBy<RelationshipEdgeEntity> { it.userConfirmed }
                    .thenBy { it.confidence }
                    .thenBy { it.updatedAtEpochMs },
            )
        }
    val directNeighbors = strongestEdgeByNeighbor.keys.sortedWith(
        compareByDescending<String> { intensityById[it]?.interactionCount ?: 0 }
            .thenByDescending { strongestEdgeByNeighbor[it]?.userConfirmed == true }
            .thenByDescending { strongestEdgeByNeighbor[it]?.confidence ?: 0.0 }
            .thenByDescending { strongestEdgeByNeighbor[it]?.updatedAtEpochMs ?: 0L }
            .thenBy { it },
    ).take(maxRootNeighbors.coerceAtLeast(1))
    val hopByNode = linkedMapOf(rootId to 0).apply { directNeighbors.forEach { put(it, 1) } }
    val visibleNodes = hopByNode.keys
    return RelationshipGraphProjection(
        rootId = rootId,
        isEgoView = false,
        hopByNode = hopByNode,
        ringByNode = ringByNode,
        edges = validEdges.filter { it.fromContactId in visibleNodes && it.toContactId in visibleNodes },
    )
}

internal fun relationshipGraphRing(interactionCount: Int): RelationshipGraphRing = when {
    interactionCount >= 8 -> RelationshipGraphRing.INNER
    interactionCount >= 3 -> RelationshipGraphRing.MIDDLE
    interactionCount > 0 -> RelationshipGraphRing.OUTER
    else -> RelationshipGraphRing.UNKNOWN
}

internal fun relationshipGraphPresentation(projection: RelationshipGraphProjection): Map<String, ForceGraphNodePresentation> =
    projection.hopByNode.mapValues { (id, hop) ->
        if (projection.isEgoView && id == RelationshipPersonIds.SELF) {
            ForceGraphNodePresentation(
                hopDistance = hop,
                opacity = 0.15f,
                showLabel = false,
                isBackground = true,
                ring = projection.ringByNode[id] ?: RelationshipGraphRing.UNKNOWN,
            )
        } else if (!projection.isEgoView || hop <= 1) {
            ForceGraphNodePresentation(
                hopDistance = hop,
                ring = projection.ringByNode[id] ?: RelationshipGraphRing.UNKNOWN,
            )
        } else {
            ForceGraphNodePresentation(
                hopDistance = hop,
                opacity = 0.40f,
                showLabel = false,
                ring = projection.ringByNode[id] ?: RelationshipGraphRing.UNKNOWN,
            )
        }
    }.toMutableMap().apply {
        if (!(projection.isEgoView && projection.rootId == RelationshipPersonIds.SELF)) {
            this[projection.rootId] = ForceGraphNodePresentation(
                hopDistance = 0,
                ring = projection.ringByNode[projection.rootId] ?: RelationshipGraphRing.UNKNOWN,
            )
        }
    }
