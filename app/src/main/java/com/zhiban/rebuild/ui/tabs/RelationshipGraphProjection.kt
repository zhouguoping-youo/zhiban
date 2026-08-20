package com.zhiban.rebuild.ui.tabs

import com.zhiban.rebuild.data.contact.RelationshipEdgeEntity
import com.zhiban.rebuild.data.contact.RelationshipPersonIds

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
    val edges: List<RelationshipEdgeEntity>,
)

internal data class ForceGraphNodePresentation(
    val hopDistance: Int = 0,
    val opacity: Float = 1f,
    val showLabel: Boolean = true,
    val isBackground: Boolean = false,
)

internal fun projectRelationshipGraph(
    rootId: String,
    peopleIds: Set<String>,
    edges: List<RelationshipEdgeEntity>,
    ownerId: String = RelationshipPersonIds.SELF,
    maxEgoHops: Int = 2,
): RelationshipGraphProjection {
    val validEdges = edges.filter { it.fromContactId in peopleIds && it.toContactId in peopleIds }
    if (rootId == ownerId) {
        val nodes = validEdges.flatMapTo(linkedSetOf()) { edge ->
            listOf(edge.fromContactId, edge.toContactId)
        }
        nodes += rootId
        return RelationshipGraphProjection(
            rootId = rootId,
            isEgoView = false,
            hopByNode = nodes.associateWith { if (it == rootId) 0 else 1 },
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
        edges = validEdges.filter { it.fromContactId in visibleNodes && it.toContactId in visibleNodes },
    )
}

internal fun relationshipGraphPresentation(projection: RelationshipGraphProjection): Map<String, ForceGraphNodePresentation> =
    projection.hopByNode.mapValues { (id, hop) ->
        if (!projection.isEgoView || hop <= 1) {
            ForceGraphNodePresentation(hopDistance = hop)
        } else {
            ForceGraphNodePresentation(
                hopDistance = hop,
                opacity = 0.40f,
                showLabel = false,
            )
        }
    }.toMutableMap().apply {
        this[projection.rootId] = ForceGraphNodePresentation(hopDistance = 0)
    }
