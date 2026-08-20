package com.zhiban.rebuild.ui.tabs

internal const val RELATIONSHIP_EDGE_LABEL_ZOOM_THRESHOLD = 1.18f
internal const val RELATIONSHIP_GRAPH_DOUBLE_TAP_WINDOW_MS = 280L

internal fun isRelationshipGraphDoubleTap(previousNodeId: String?, nodeId: String, previousAtEpochMs: Long, currentAtEpochMs: Long): Boolean =
    previousNodeId == nodeId && currentAtEpochMs - previousAtEpochMs in 1..RELATIONSHIP_GRAPH_DOUBLE_TAP_WINDOW_MS

internal fun shouldShowRelationshipEdgeLabel(scale: Float, selected: Boolean, touchesSelectedNode: Boolean): Boolean =
    selected || touchesSelectedNode || scale >= RELATIONSHIP_EDGE_LABEL_ZOOM_THRESHOLD

internal fun relationshipGraphEdgeCaption(link: ForceGraphLink): String = if (link.isInferred && !link.displayLabel.contains("推测")) {
    "${link.displayLabel} · 推测"
} else {
    link.displayLabel
}
