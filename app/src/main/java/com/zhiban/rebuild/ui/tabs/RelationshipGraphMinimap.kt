package com.zhiban.rebuild.ui.tabs

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.zhiban.rebuild.ui.theme.RelationshipGraphColors
import com.zhiban.rebuild.ui.theme.ZhiBanRadius
import kotlin.math.max
import kotlin.math.min

internal fun relationshipGraphWorldBounds(points: Collection<Offset>): Rect? {
    if (points.isEmpty()) return null
    val left = points.minOf(Offset::x)
    val top = points.minOf(Offset::y)
    val right = points.maxOf(Offset::x)
    val bottom = points.maxOf(Offset::y)
    val padding = max(24f, max(right - left, bottom - top) * 0.12f)
    return Rect(left - padding, top - padding, right + padding, bottom + padding)
}

internal fun relationshipGraphMinimapPoint(point: Offset, worldBounds: Rect, size: Size): Offset {
    val width = worldBounds.width.coerceAtLeast(1f)
    val height = worldBounds.height.coerceAtLeast(1f)
    return Offset(
        ((point.x - worldBounds.left) / width * size.width).coerceIn(0f, size.width),
        ((point.y - worldBounds.top) / height * size.height).coerceIn(0f, size.height),
    )
}

internal fun relationshipGraphViewportInWorld(graphOffset: Offset, graphScale: Float, size: Size): Rect {
    val scale = graphScale.coerceAtLeast(0.01f)
    return Rect(
        left = -graphOffset.x / scale,
        top = -graphOffset.y / scale,
        right = (size.width - graphOffset.x) / scale,
        bottom = (size.height - graphOffset.y) / scale,
    )
}

@Composable
internal fun RelationshipGraphMinimap(
    modifier: Modifier = Modifier,
    positions: Map<String, ForceBody>,
    links: List<ForceGraphLink>,
    rootId: String,
    graphScale: Float,
    graphOffset: Offset,
    graphSize: Size,
    graphColors: RelationshipGraphColors,
    onClick: () -> Unit,
) {
    val surface = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
    val outline = MaterialTheme.colorScheme.outlineVariant
    Box(
        modifier
            .size(width = 96.dp, height = 72.dp)
            .clip(RoundedCornerShape(ZhiBanRadius.Card))
            .background(surface)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "关系图缩略图，点击回到我的视角" },
    ) {
        Canvas(Modifier.matchParentSize()) {
            val worldBounds = relationshipGraphWorldBounds(positions.values.map(ForceBody::position)) ?: return@Canvas
            links.forEach { link ->
                val from = positions[link.fromId]?.position ?: return@forEach
                val to = positions[link.toId]?.position ?: return@forEach
                drawLine(
                    color = outline.copy(alpha = 0.42f),
                    start = relationshipGraphMinimapPoint(from, worldBounds, size),
                    end = relationshipGraphMinimapPoint(to, worldBounds, size),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            positions.forEach { (id, body) ->
                val point = relationshipGraphMinimapPoint(body.position, worldBounds, size)
                val color = if (id == rootId) graphColors.focusNode else graphColors.contactNode
                drawCircle(color = color, radius = if (id == rootId) 3.dp.toPx() else 2.dp.toPx(), center = point)
            }
            val viewport = relationshipGraphViewportInWorld(graphOffset, graphScale, graphSize)
            val topLeft = relationshipGraphMinimapPoint(Offset(viewport.left, viewport.top), worldBounds, size)
            val bottomRight = relationshipGraphMinimapPoint(Offset(viewport.right, viewport.bottom), worldBounds, size)
            drawRect(
                color = graphColors.focusNode.copy(alpha = 0.75f),
                topLeft = topLeft,
                size = Size(
                    width = min(size.width - topLeft.x, bottomRight.x - topLeft.x).coerceAtLeast(2f),
                    height = min(size.height - topLeft.y, bottomRight.y - topLeft.y).coerceAtLeast(2f),
                ),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()),
            )
        }
    }
}
