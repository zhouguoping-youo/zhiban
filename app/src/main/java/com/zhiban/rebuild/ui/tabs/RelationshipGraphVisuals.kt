package com.zhiban.rebuild.ui.tabs

internal const val RELATIONSHIP_EDGE_LABEL_ZOOM_THRESHOLD = 1.18f

internal fun shouldShowRelationshipEdgeLabel(scale: Float, selected: Boolean, touchesSelectedNode: Boolean): Boolean =
    selected || touchesSelectedNode || scale >= RELATIONSHIP_EDGE_LABEL_ZOOM_THRESHOLD

internal fun relationshipGraphEdgeCaption(link: ForceGraphLink): String = if (link.isInferred && !link.displayLabel.contains("推测")) {
    "${link.displayLabel} · 推测"
} else {
    link.displayLabel
}
