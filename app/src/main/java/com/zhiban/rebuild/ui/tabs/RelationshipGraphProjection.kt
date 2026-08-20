package com.zhiban.rebuild.ui.tabs

import com.zhiban.rebuild.data.contact.RelationshipEdgeEntity
import com.zhiban.rebuild.data.contact.RelationshipPersonIds
import com.zhiban.rebuild.data.interaction.ContactInteractionIntensity

/**
 * A small, UI-independent projection of the relationship graph.
 *
 * The persisted graph is intentionally kept separate from what is drawn.  Root view shows the
 * complete connected graph, while a contact ego view is limited to two hops so that expanding a
 * node never silently turns the canvas into an unreadable all-contact list.
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
    interactionIntensity: List<ContactInteractionIntensity> = emptyList(),
): RelationshipGraphProjection {
    val validEdges = edges.filter { it.fromContactId in peopleIds && it.toContactId in peopleIds }
    val intensityById = interactionIntensity.associateBy { it.contactId }
    val ringByNode = peopleIds.associateWith { id ->
        relationshipGraphRing(intensityById[id]?.interactionCount ?: 0)
    }
    if (rootId == ownerId) {
        val nodes = validEdges.flatMapTo(linkedSetOf()) { edge ->
            listOf(edge.fromContactId, edge.toContactId)
        }
        nodes += rootId
        return RelationshipGraphProjection(
            rootId = rootId,
            isEgoView = false,
            hopByNode = nodes.associateWith { if (it == rootId) 0 else 1 },
            ringByNode = ringByNode,
            edges = validEdges,
        )
    }

    val adjacency = buildMap<String, MutableSet<String>> {
        validEdges.forEach { edge ->
            getOrPut(edge.fromContactId) { linkedSetOf() }.add(edge.toContactId)
            getOrPut(edge.toContactId) { linkedSetOf() }.add(edge.fromContactId)
        }
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

internal fun relationshipGraphRing(interactionCount: Int): RelationshipGraphRing = when {
    interactionCount >= 8 -> RelationshipGraphRing.INNER
    interactionCount >= 3 -> RelationshipGraphRing.MIDDLE
    interactionCount > 0 -> RelationshipGraphRing.OUTER
    else -> RelationshipGraphRing.UNKNOWN
}

internal fun relationshipGraphPresentation(projection: RelationshipGraphProjection): Map<String, ForceGraphNodePresentation> =
    projection.hopByNode.mapValues { (id, hop) ->
        if (!projection.isEgoView || hop <= 1) {
            ForceGraphNodePresentation(hopDistance = hop, ring = projection.ringByNode[id] ?: RelationshipGraphRing.UNKNOWN)
        } else {
            ForceGraphNodePresentation(
                hopDistance = hop,
                opacity = 0.40f,
                showLabel = false,
                ring = projection.ringByNode[id] ?: RelationshipGraphRing.UNKNOWN,
            )
        }
    }.toMutableMap().apply {
        this[projection.rootId] = ForceGraphNodePresentation(
            hopDistance = 0,
            ring = projection.ringByNode[projection.rootId] ?: RelationshipGraphRing.UNKNOWN,
        )
    }
