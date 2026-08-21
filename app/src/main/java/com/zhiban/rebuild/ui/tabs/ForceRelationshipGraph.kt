package com.zhiban.rebuild.ui.tabs

import android.os.SystemClock
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhiban.rebuild.data.contact.RelationshipEdgeEntity
import com.zhiban.rebuild.data.contact.RelationshipPersonIds
import com.zhiban.rebuild.relationship.RelationshipGroup
import com.zhiban.rebuild.relationship.RelationshipTaxonomy
import com.zhiban.rebuild.ui.theme.LocalRelationshipGraphColors
import com.zhiban.rebuild.ui.theme.RelationshipGraphColors
import com.zhiban.rebuild.ui.theme.ZhiBanRadius
import com.zhiban.rebuild.ui.theme.ZhiBanSize
import com.zhiban.rebuild.ui.theme.ZhiBanSpacing
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val WorkRelations = RelationshipTaxonomy.selectableDefinitions
    .filter { it.group == RelationshipGroup.WORK }
    .mapTo(hashSetOf()) { it.code }

internal enum class ForceGraphNodeKind { FOCUS, CONTACT, WORK }

internal const val FORCE_PAIRWISE_NODE_LIMIT = 160

private data class GraphPaintKey(val color: Int, val textSize: Float, val bold: Boolean)

internal data class ForceGraphNode(
    val id: String,
    val name: String,
    val kind: ForceGraphNodeKind,
    val company: String? = null,
    val title: String? = null,
    val presentation: ForceGraphNodePresentation = ForceGraphNodePresentation(),
)

internal data class ForceGraphLink(
    val fromId: String,
    val toId: String,
    val relationType: String,
    val displayLabel: String,
    val confidence: Float,
    val isInferred: Boolean,
    val isHistorical: Boolean = false,
    val evidenceLabel: String? = null,
)

internal data class ForceGraphModel(val nodes: List<ForceGraphNode>, val links: List<ForceGraphLink>)

internal data class ForceBody(
    var position: Offset,
    var velocity: Offset = Offset.Zero,
    var dragged: Boolean = false,
    val ring: RelationshipGraphRing = RelationshipGraphRing.UNKNOWN,
)

internal fun buildForceGraphModel(
    rootId: String,
    peopleById: Map<String, RelationshipPersonUi>,
    edges: List<RelationshipEdgeEntity>,
    presentationById: Map<String, ForceGraphNodePresentation> = emptyMap(),
): ForceGraphModel {
    val personIds = edges.flatMap { listOf(it.fromContactId, it.toContactId) }
        .toMutableSet()
        .apply { add(rootId) }
        .filterTo(linkedSetOf()) { it in peopleById }

    val incidentTypes = edges.flatMap { edge ->
        listOf(edge.fromContactId to edge.relationType, edge.toContactId to edge.relationType)
    }.groupBy({ it.first }, { it.second })

    val personNodes = personIds.mapNotNull { id ->
        val person = peopleById[id] ?: return@mapNotNull null
        val kind = when {
            id == rootId || person.isOwner -> ForceGraphNodeKind.FOCUS
            incidentTypes[id].orEmpty().any(WorkRelations::contains) -> ForceGraphNodeKind.WORK
            else -> ForceGraphNodeKind.CONTACT
        }
        ForceGraphNode(
            id = id,
            name = if (person.isOwner) "我" else person.displayName,
            kind = kind,
            company = person.company?.trim()?.takeIf(String::isNotBlank),
            title = person.title?.trim()?.takeIf(String::isNotBlank),
            presentation = presentationById[id] ?: ForceGraphNodePresentation(),
        )
    }

    val relationLinks = edges.mapNotNull { edge ->
        if (edge.fromContactId !in personIds || edge.toContactId !in personIds) return@mapNotNull null
        val fromCompany = peopleById[edge.fromContactId]?.company?.trim()?.takeIf(String::isNotBlank)
        val toCompany = peopleById[edge.toContactId]?.company?.trim()?.takeIf(String::isNotBlank)
        val sharedCompany = fromCompany?.takeIf {
            toCompany != null && normalizeCompanyKey(it) == normalizeCompanyKey(toCompany)
        }
        ForceGraphLink(
            fromId = edge.fromContactId,
            toId = edge.toContactId,
            relationType = edge.relationType,
            displayLabel = edge.displayRelationLabel(),
            confidence = edge.confidence.toFloat().coerceIn(0f, 1f),
            isInferred = !edge.userConfirmed,
            isHistorical = edge.isHistoricalRelationship(),
            evidenceLabel = sharedCompany ?: edge.evidenceDigest
                .takeIf { edge.isInferredEvidenceRelationship() }
                ?.substringAfter('：', missingDelimiterValue = edge.evidenceDigest),
        )
    }
    return ForceGraphModel(personNodes, relationLinks)
}

private fun normalizeCompanyKey(company: String): String = company.trim().lowercase()

internal fun seedForceBodies(
    nodeIds: List<String>,
    rootId: String,
    width: Float,
    height: Float,
    ringByNode: Map<String, RelationshipGraphRing> = emptyMap(),
): Map<String, ForceBody> {
    val center = Offset(width / 2f, height / 2f)
    val radius = min(width, height) * 0.28f
    val neighborIds = nodeIds.filterNot { it == rootId }
    val ringOrdinals = neighborIds.groupingBy { ringByNode[it] ?: RelationshipGraphRing.UNKNOWN }.eachCount()
    val ringOffsets = ringOrdinals.keys.associateWith { ring ->
        neighborIds.takeWhile { (ringByNode[it] ?: RelationshipGraphRing.UNKNOWN) != ring }.size
    }
    return nodeIds.associateWith { id ->
        val position = if (id == rootId) {
            center
        } else {
            val ordinal = neighborIds.indexOf(id).coerceAtLeast(0)
            val ring = ringByNode[id] ?: RelationshipGraphRing.UNKNOWN
            val ringIds = neighborIds.filter { (ringByNode[it] ?: RelationshipGraphRing.UNKNOWN) == ring }
            val ringOrdinal = ringIds.indexOf(id).coerceAtLeast(0)
            val ringRadius = when (ring) {
                RelationshipGraphRing.INNER -> radius * 0.68f
                RelationshipGraphRing.MIDDLE -> radius * 1.12f
                RelationshipGraphRing.OUTER -> radius * 1.48f
                RelationshipGraphRing.UNKNOWN -> radius
            }
            val angle = if (ringIds.size == 2) {
                -5.0 * PI / 6.0 + ringOrdinal * (2.0 * PI / 3.0)
            } else {
                val phase = (ringOffsets[ring] ?: ordinal) * 0.34
                -PI / 2.0 + phase + ringOrdinal * (2.0 * PI / max(1, ringIds.size))
            }
            val jitter = ((id.hashCode().toLong() and 0xffffL) / 65535f - 0.5f) * radius * 0.08f
            Offset(
                center.x + (ringRadius + jitter) * cos(angle).toFloat(),
                center.y + (ringRadius - jitter) * sin(angle).toFloat(),
            )
        }
        ForceBody(position, ring = ringByNode[id] ?: RelationshipGraphRing.UNKNOWN)
    }
}

internal fun estimateForcePairCount(nodeCount: Int): Long = if (nodeCount <= FORCE_PAIRWISE_NODE_LIMIT) {
    nodeCount.toLong() * (nodeCount - 1L) / 2L
} else {
    0L
}

private data class RepulsionState(val ids: List<String>, val bodies: Map<String, ForceBody>, val forces: MutableMap<String, Offset>, val repulsion: Float)

private fun applyPairwiseRepulsion(state: RepulsionState) {
    val ids = state.ids
    val bodies = state.bodies
    val forces = state.forces
    for (firstIndex in 0 until ids.lastIndex) {
        for (secondIndex in firstIndex + 1 until ids.size) {
            val first = bodies.getValue(ids[firstIndex])
            val second = bodies.getValue(ids[secondIndex])
            var delta = second.position - first.position
            var distance = hypot(delta.x, delta.y)
            if (distance < 1f) {
                delta = Offset(if (firstIndex % 2 == 0) 1f else -1f, 0.5f)
                distance = hypot(delta.x, delta.y)
            }
            val direction = delta / distance
            val magnitude = state.repulsion / max(distance * distance, 64f)
            val push = direction * magnitude
            forces[ids[firstIndex]] = forces.getValue(ids[firstIndex]) - push
            forces[ids[secondIndex]] = forces.getValue(ids[secondIndex]) + push
        }
    }
}

internal fun advanceForceSimulation(
    bodies: MutableMap<String, ForceBody>,
    links: List<ForceGraphLink>,
    rootId: String,
    width: Float,
    height: Float,
    density: Float,
    timeScale: Float,
) {
    if (bodies.size < 2 || width <= 0f || height <= 0f) return
    val ids = bodies.keys.toList()
    val forces = ids.associateWith { Offset.Zero }.toMutableMap()
    val repulsion = 8_200f * density * density
    val desiredLength = 106f * density

    if (ids.size <= FORCE_PAIRWISE_NODE_LIMIT) {
        applyPairwiseRepulsion(RepulsionState(ids, bodies, forces, repulsion))
    }

    links.forEach { link ->
        val from = bodies[link.fromId] ?: return@forEach
        val to = bodies[link.toId] ?: return@forEach
        val delta = to.position - from.position
        val distance = hypot(delta.x, delta.y).coerceAtLeast(1f)
        val direction = delta / distance
        val linkLength = desiredLength + (1f - link.confidence) * 38f * density
        val spring = direction * ((distance - linkLength) * 0.014f)
        forces[link.fromId] = forces.getValue(link.fromId) + spring
        forces[link.toId] = forces.getValue(link.toId) - spring
    }

    val center = Offset(width / 2f, height / 2f)
    val margin = 42f * density
    ids.forEach { id ->
        val body = bodies.getValue(id)
        if (body.dragged) {
            body.velocity = Offset.Zero
            return@forEach
        }
        val centering = (center - body.position) * if (id == rootId) 0.0048f else 0.0015f
        val ringForce = if (id == rootId || body.ring == RelationshipGraphRing.UNKNOWN) {
            Offset.Zero
        } else {
            val offset = body.position - center
            val currentRadius = offset.getDistance().coerceAtLeast(1f)
            val targetRadius = when (body.ring) {
                RelationshipGraphRing.INNER -> min(width, height) * 0.19f
                RelationshipGraphRing.MIDDLE -> min(width, height) * 0.31f
                RelationshipGraphRing.OUTER -> min(width, height) * 0.42f
                RelationshipGraphRing.UNKNOWN -> currentRadius
            }
            offset / currentRadius * ((targetRadius - currentRadius) * 0.008f)
        }
        val acceleration = forces.getValue(id) + centering + ringForce
        body.velocity = (body.velocity + acceleration * timeScale) * 0.86f
        val next = body.position + body.velocity * timeScale
        body.position = Offset(
            next.x.coerceIn(margin, max(margin, width - margin)),
            next.y.coerceIn(margin, max(margin, height - margin)),
        )
    }
}

@Composable
internal fun ForceRelationshipGraphCanvas(
    rootId: String,
    peopleById: Map<String, RelationshipPersonUi>,
    edges: List<RelationshipEdgeEntity>,
    onSelectContact: (String) -> Unit,
    presentationById: Map<String, ForceGraphNodePresentation> = emptyMap(),
    onSwitchEgo: ((String) -> Unit)? = null,
) {
    val model = remember(rootId, peopleById, edges, presentationById) {
        buildForceGraphModel(rootId, peopleById, edges, presentationById)
    }
    val nodeById = remember(model.nodes) { model.nodes.associateBy(ForceGraphNode::id) }
    if (model.nodes.size < 2) return

    val density = LocalDensity.current
    val densityValue = density.density
    val canvasSurface = MaterialTheme.colorScheme.surface
    val lineColor = MaterialTheme.colorScheme.onSurfaceVariant
    val labelColor = MaterialTheme.colorScheme.onSurface
    val outlineColor = MaterialTheme.colorScheme.outlineVariant
    val graphColors = LocalRelationshipGraphColors.current
    val bodies = remember(model.nodes.map(ForceGraphNode::id), rootId) { mutableStateMapOf<String, ForceBody>() }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var frameTick by remember { mutableIntStateOf(0) }
    var graphScale by remember(rootId) { mutableFloatStateOf(1f) }
    var graphOffset by remember(rootId) { mutableStateOf(Offset.Zero) }
    var selectedNodeId by remember { mutableStateOf<String?>(null) }
    var lastTapNodeId by remember { mutableStateOf<String?>(null) }
    var lastTapAtEpochMs by remember { mutableStateOf(0L) }
    var simulationPulse by remember { mutableIntStateOf(0) }
    var viewportAnimationJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val textPaintCache = remember { mutableMapOf<GraphPaintKey, android.graphics.Paint>() }
    val touchSlop = with(density) { 8.dp.toPx() }
    val hitRadius = with(density) { 28.dp.toPx() }

    fun screenPosition(world: Offset): Offset = Offset(
        world.x * graphScale + graphOffset.x,
        world.y * graphScale + graphOffset.y,
    )

    fun animateViewport(targetScale: Float, targetOffset: Offset) {
        viewportAnimationJob?.cancel()
        val startScale = graphScale
        val startOffset = graphOffset
        viewportAnimationJob = scope.launch {
            animate(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = tween(durationMillis = 380, easing = FastOutSlowInEasing),
            ) { progress, _ ->
                graphScale = startScale + (targetScale - startScale) * progress
                graphOffset = startOffset + (targetOffset - startOffset) * progress
            }
        }
    }

    fun focusNode(nodeId: String, zoom: Float = 1.12f) {
        val body = bodies[nodeId] ?: return
        val nextScale = zoom.coerceIn(0.72f, 2.8f)
        animateViewport(
            targetScale = nextScale,
            targetOffset = Offset(
                canvasSize.width / 2f - body.position.x * nextScale,
                canvasSize.height / 2f - body.position.y * nextScale,
            ),
        )
    }

    fun resetViewport() {
        val rootBody = bodies[rootId]
        animateViewport(
            targetScale = 1f,
            targetOffset = if (rootBody == null) {
                Offset.Zero
            } else {
                Offset(
                    canvasSize.width / 2f - rootBody.position.x,
                    canvasSize.height / 2f - rootBody.position.y,
                )
            },
        )
        selectedNodeId = null
        simulationPulse += 1
    }

    LaunchedEffect(model.nodes.map(ForceGraphNode::id), canvasSize, rootId, simulationPulse) {
        if (canvasSize == IntSize.Zero) return@LaunchedEffect
        if (bodies.keys != model.nodes.map(ForceGraphNode::id).toSet()) {
            bodies.clear()
            bodies.putAll(
                seedForceBodies(
                    nodeIds = model.nodes.map(ForceGraphNode::id),
                    rootId = rootId,
                    width = canvasSize.width.toFloat(),
                    height = canvasSize.height.toFloat(),
                    ringByNode = model.nodes.associate { it.id to it.presentation.ring },
                ),
            )
        }
        var previousFrame = 0L
        var calmFrames = 0
        var frameCount = 0
        while (isActive && calmFrames < 120 && frameCount < 300) {
            withFrameNanos { frameNanos ->
                val elapsed = if (previousFrame == 0L) {
                    1f
                } else {
                    ((frameNanos - previousFrame) / 16_666_667f).coerceIn(0.35f, 1.8f)
                }
                previousFrame = frameNanos
                advanceForceSimulation(
                    bodies = bodies,
                    links = model.links,
                    rootId = rootId,
                    width = canvasSize.width.toFloat(),
                    height = canvasSize.height.toFloat(),
                    density = densityValue,
                    timeScale = elapsed,
                )
                val speed = bodies.values.maxOfOrNull { hypot(it.velocity.x, it.velocity.y) } ?: 0f
                calmFrames = if (speed < 0.025f) calmFrames + 1 else 0
                frameCount += 1
                frameTick += 1
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val graphHeight = when {
            maxWidth < 340.dp -> 408.dp
            maxWidth < 480.dp -> 448.dp
            else -> 520.dp
        }
        Column(
            Modifier.fillMaxWidth().widthIn(max = 720.dp),
            verticalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Sm),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(graphHeight)
                    .clip(RoundedCornerShape(ZhiBanRadius.Card))
                    .background(canvasSurface)
                    .border(1.dp, outlineColor, RoundedCornerShape(ZhiBanRadius.Card)),
            ) {
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(graphHeight)
                        .onSizeChanged { canvasSize = it }
                        .pointerInput(model.nodes.map(ForceGraphNode::id), canvasSize) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                viewportAnimationJob?.cancel()
                                down.consume()
                                val downPosition = down.position
                                val activeNodeId = bodies.entries
                                    .minByOrNull { (_, body) ->
                                        val point = screenPosition(body.position)
                                        hypot(point.x - downPosition.x, point.y - downPosition.y)
                                    }
                                    ?.takeIf { (_, body) ->
                                        val point = screenPosition(body.position)
                                        hypot(point.x - downPosition.x, point.y - downPosition.y) <= hitRadius
                                    }
                                    ?.key
                                var movedDistance = 0f
                                var nodeDragging = false
                                var longPressed = false
                                var pinching = false
                                val downAtEpochMs = SystemClock.uptimeMillis()
                                var hasPressedPointers: Boolean
                                do {
                                    val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                                    val pressed = event.changes.filter { it.pressed }
                                    hasPressedPointers = pressed.isNotEmpty()
                                    if (pressed.size >= 2) {
                                        pinching = true
                                        activeNodeId?.let { bodies[it]?.dragged = false }
                                        val oldScale = graphScale
                                        val nextScale = (oldScale * event.calculateZoom()).coerceIn(0.72f, 2.8f)
                                        val centroid = event.calculateCentroid(useCurrent = true)
                                        val zoomRatio = nextScale / oldScale
                                        graphOffset =
                                            centroid - (centroid - graphOffset) * zoomRatio + event.calculatePan()
                                        graphScale = nextScale
                                        event.changes.forEach { it.consume() }
                                    } else if (pressed.size == 1) {
                                        val change = pressed.first()
                                        val delta = change.position - change.previousPosition
                                        movedDistance += hypot(delta.x, delta.y)
                                        if (activeNodeId != null && !nodeDragging && !longPressed &&
                                            SystemClock.uptimeMillis() - downAtEpochMs >= 500L
                                        ) {
                                            longPressed = true
                                            nodeDragging = true
                                        }
                                        if (activeNodeId != null && (nodeDragging || movedDistance > touchSlop)) {
                                            nodeDragging = true
                                            bodies[activeNodeId]?.let { body ->
                                                body.dragged = true
                                                body.position += delta / graphScale
                                                body.velocity = Offset.Zero
                                                frameTick += 1
                                            }
                                        } else if (activeNodeId == null && movedDistance > touchSlop) {
                                            graphOffset += delta
                                        }
                                        if (delta != Offset.Zero) change.consume()
                                    }
                                } while (hasPressedPointers)
                                activeNodeId?.let { bodies[it]?.dragged = false }
                                if (!pinching && !nodeDragging && !longPressed && activeNodeId != null) {
                                    selectedNodeId = activeNodeId
                                    focusNode(activeNodeId)
                                    val now = SystemClock.uptimeMillis()
                                    if (isRelationshipGraphDoubleTap(lastTapNodeId, activeNodeId, lastTapAtEpochMs, now)) {
                                        lastTapNodeId = null
                                        lastTapAtEpochMs = 0L
                                        onSwitchEgo?.invoke(activeNodeId)
                                    } else {
                                        lastTapNodeId = activeNodeId
                                        lastTapAtEpochMs = now
                                        onSwitchEgo?.invoke(activeNodeId)
                                    }
                                } else if (nodeDragging && model.nodes.size <= FORCE_PAIRWISE_NODE_LIMIT) {
                                    simulationPulse += 1
                                }
                            }
                        },
                ) {
                    @Suppress("UNUSED_VARIABLE")
                    val redraw = frameTick
                    val viewportPadding = 72.dp.toPx()
                    fun visible(point: Offset): Boolean = point.x in -viewportPadding..(size.width + viewportPadding) &&
                        point.y in -viewportPadding..(size.height + viewportPadding)
                    val rootCenter = bodies[rootId]?.position?.let(::screenPosition)
                        ?: Offset(size.width / 2f, size.height / 2f)
                    listOf(0.19f, 0.31f, 0.42f).forEach { ratio ->
                        drawCircle(
                            color = outlineColor.copy(alpha = 0.15f),
                            radius = min(size.width, size.height) * ratio * graphScale,
                            center = rootCenter,
                            style = Stroke(width = 0.8f * densityValue),
                        )
                    }
                    model.links.forEach { link ->
                        val from = bodies[link.fromId]?.position?.let(::screenPosition) ?: return@forEach
                        val to = bodies[link.toId]?.position?.let(::screenPosition) ?: return@forEach
                        if (!visible(from) && !visible(to)) return@forEach
                        val edgeOpacity = min(
                            nodeById[link.fromId]?.presentation?.opacity ?: 1f,
                            nodeById[link.toId]?.presentation?.opacity ?: 1f,
                        )
                        val touchesSelectedNode = selectedNodeId == link.fromId || selectedNodeId == link.toId
                        val touchesRoot = rootId == link.fromId || rootId == link.toId
                        drawLine(
                            color = if (link.evidenceLabel != null) {
                                graphColors.sharedCompany.copy(alpha = 0.58f * edgeOpacity)
                            } else if (touchesSelectedNode || touchesRoot) {
                                graphColors.focusNode.copy(
                                    alpha = when {
                                        link.isInferred -> 0.42f
                                        link.isHistorical -> 0.48f
                                        else -> 0.72f
                                    } * edgeOpacity,
                                )
                            } else {
                                lineColor.copy(
                                    alpha = when {
                                        link.isInferred -> 0.28f
                                        link.isHistorical -> 0.34f
                                        else -> 0.48f
                                    } * edgeOpacity,
                                )
                            },
                            start = from,
                            end = to,
                            strokeWidth = (1.2f + link.confidence * 1.8f) * densityValue,
                            cap = StrokeCap.Round,
                            pathEffect = when {
                                link.isInferred -> PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
                                link.isHistorical -> PathEffect.dashPathEffect(floatArrayOf(16f, 7f))
                                else -> null
                            },
                        )
                        val showEdgeLabel = shouldShowRelationshipEdgeLabel(
                            scale = graphScale,
                            selected = selectedNodeId != null && touchesSelectedNode,
                            touchesSelectedNode = touchesSelectedNode,
                        )
                        val relationshipText = relationshipGraphEdgeCaption(link)
                        if (showEdgeLabel && relationshipText.isNotBlank()) {
                            val center = (from + to) / 2f
                            val labelWidth = max(50.dp.toPx(), (relationshipText.length * 13 + 16).dp.toPx())
                            val labelHeight = 22.dp.toPx()
                            drawRoundRect(
                                color = canvasSurface.copy(alpha = 0.96f),
                                topLeft = Offset(center.x - labelWidth / 2f, center.y - labelHeight / 2f),
                                size = Size(labelWidth, labelHeight),
                                cornerRadius = CornerRadius(labelHeight / 2f),
                            )
                            drawIntoCanvas { canvas ->
                                val paint = textPaintCache.getOrPut(
                                    GraphPaintKey(
                                        color = if (link.evidenceLabel != null) {
                                            graphColors.sharedCompany.toArgb()
                                        } else {
                                            labelColor.toArgb()
                                        },
                                        textSize = 10.sp.toPx(),
                                        bold = true,
                                    ),
                                ) {
                                    android.graphics.Paint().apply {
                                        isAntiAlias = true
                                        textAlign = android.graphics.Paint.Align.CENTER
                                        textSize = 10.sp.toPx()
                                        typeface = android.graphics.Typeface.create(
                                            android.graphics.Typeface.DEFAULT,
                                            android.graphics.Typeface.BOLD,
                                        )
                                    }
                                }.also { it.color = if (link.evidenceLabel != null) graphColors.sharedCompany.toArgb() else labelColor.toArgb() }
                                canvas.nativeCanvas.drawText(
                                    relationshipText,
                                    center.x,
                                    center.y - (paint.ascent() + paint.descent()) / 2f,
                                    paint,
                                )
                            }
                        }
                    }

                    model.nodes.forEach { node ->
                        val center = bodies[node.id]?.position?.let(::screenPosition) ?: return@forEach
                        if (!visible(center)) return@forEach
                        val selected = node.id == selectedNodeId
                        val visualRadius = with(density) {
                            (if (node.kind == ForceGraphNodeKind.FOCUS) 31.dp else 25.dp).toPx()
                        } * graphScale.coerceIn(0.85f, 1.35f)
                        val color = nodeColor(node.kind, graphColors)
                        val nodeOpacity = node.presentation.opacity
                        drawCircle(
                            Color.Black.copy(alpha = 0.08f * nodeOpacity),
                            visualRadius * 1.06f,
                            center + Offset(0f, 4.dp.toPx()),
                        )
                        if (node.kind == ForceGraphNodeKind.FOCUS || selected) {
                            drawCircle(color.copy(alpha = 0.13f * nodeOpacity), visualRadius * 1.48f, center)
                        }
                        drawCircle(color.copy(alpha = nodeOpacity), visualRadius, center)
                        drawCircle(
                            graphColors.nodeRing.copy(alpha = 0.62f * nodeOpacity),
                            visualRadius * 0.86f,
                            center,
                            style = Stroke(width = 1.4f * densityValue),
                        )
                        val initial = node.name.take(1)
                        val rootCenter =
                            bodies[rootId]?.position?.let(::screenPosition) ?: Offset(size.width / 2f, size.height / 2f)
                        val outwardDelta = center - rootCenter
                        val outwardDistance = hypot(outwardDelta.x, outwardDelta.y)
                        val outward = if (node.id == rootId || outwardDistance < 1f) {
                            Offset(0f, 1f)
                        } else {
                            outwardDelta / outwardDistance
                        }
                        val labelCenter = center + outward * (visualRadius + 17.dp.toPx())
                        val clippedName = if (node.name.length > 8) node.name.take(7) + "…" else node.name
                        val labelPaint = textPaintCache.getOrPut(
                            GraphPaintKey(labelColor.toArgb(), 12.sp.toPx(), true),
                        ) {
                            android.graphics.Paint().apply {
                                isAntiAlias = true
                                textAlign = android.graphics.Paint.Align.CENTER
                                textSize = 12.sp.toPx()
                                typeface = android.graphics.Typeface.create(
                                    android.graphics.Typeface.DEFAULT,
                                    android.graphics.Typeface.BOLD,
                                )
                            }
                        }.also { it.color = labelColor.toArgb() }
                        val nameWidth = labelPaint.measureText(clippedName) + 10.dp.toPx()
                        val nameHeight = 20.dp.toPx()
                        val safeLabelCenter = Offset(
                            labelCenter.x.coerceIn(
                                nameWidth / 2f + 4.dp.toPx(),
                                size.width - nameWidth / 2f - 4.dp.toPx(),
                            ),
                            labelCenter.y.coerceIn(
                                nameHeight / 2f + 4.dp.toPx(),
                                size.height - nameHeight / 2f - 4.dp.toPx(),
                            ),
                        )
                        if (node.presentation.showLabel) {
                            drawRoundRect(
                                color = canvasSurface.copy(alpha = 0.94f * nodeOpacity),
                                topLeft = Offset(safeLabelCenter.x - nameWidth / 2f, safeLabelCenter.y - nameHeight / 2f),
                                size = Size(nameWidth, nameHeight),
                                cornerRadius = CornerRadius(nameHeight / 2f),
                            )
                        }
                        drawIntoCanvas { canvas ->
                            val paint = android.graphics.Paint().apply {
                                isAntiAlias = true
                                this.color = nodeContentColor(node.kind, graphColors).toArgb()
                                textAlign = android.graphics.Paint.Align.CENTER
                                textSize = 18.sp.toPx()
                                typeface =
                                    android.graphics.Typeface.create(
                                        android.graphics.Typeface.DEFAULT,
                                        android.graphics.Typeface.BOLD,
                                    )
                            }
                            canvas.nativeCanvas.drawText(
                                initial,
                                center.x,
                                center.y - (paint.ascent() + paint.descent()) / 2f,
                                paint,
                            )
                            if (node.presentation.showLabel) {
                                canvas.nativeCanvas.drawText(
                                    clippedName,
                                    safeLabelCenter.x,
                                    safeLabelCenter.y - (labelPaint.ascent() + labelPaint.descent()) / 2f,
                                    labelPaint,
                                )
                            }
                        }
                    }
                }

                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(ZhiBanSpacing.Sm),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                    tonalElevation = 2.dp,
                ) {
                    IconButton(
                        onClick = ::resetViewport,
                        modifier = Modifier.size(ZhiBanSize.TouchTarget),
                    ) {
                        Icon(Icons.Rounded.RestartAlt, contentDescription = "重置关系图视图")
                    }
                }
            }
        }
    }

    selectedNodeId?.let { nodeId ->
        val selected = model.nodes.firstOrNull { it.id == nodeId }
        if (selected != null) {
            ForceNodeDetailSheet(
                node = selected,
                model = model,
                onDismiss = { selectedNodeId = null },
                onRelatedClick = { relatedId ->
                    selectedNodeId = relatedId
                    focusNode(relatedId)
                    (onSwitchEgo ?: onSelectContact).invoke(relatedId)
                },
            )
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ForceNodeDetailSheet(node: ForceGraphNode, model: ForceGraphModel, onDismiss: () -> Unit, onRelatedClick: (String) -> Unit) {
    val graphColors = LocalRelationshipGraphColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val maxSheetHeight = LocalConfiguration.current.screenHeightDp.dp * 0.70f
    val relatedLinks = model.links.filter { it.fromId == node.id || it.toId == node.id }
    val strength = relatedLinks.filter { it.relationType != "WORKS_AT" }
        .map(ForceGraphLink::confidence)
        .average()
        .takeUnless(Double::isNaN)
    com.zhiban.rebuild.ui.components.ZhiBanBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = maxSheetHeight)
                .padding(horizontal = ZhiBanSpacing.PageHorizontal)
                .padding(bottom = ZhiBanSpacing.Xl),
        ) {
            ForceNodeHeader(node = node, graphColors = graphColors, onDismiss = onDismiss)
            if (strength != null) {
                Spacer(Modifier.height(ZhiBanSpacing.Md))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(ZhiBanRadius.Medium),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(ZhiBanSpacing.Md),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("关系强度", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${(strength * 100).toInt()}%",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
            Spacer(Modifier.height(ZhiBanSpacing.Lg))
            Text("关联对象", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(ZhiBanSpacing.Xs))
            ForceNodeRelatedList(node = node, model = model, graphColors = graphColors, onRelatedClick = onRelatedClick)
        }
    }
}

private fun nodeColor(kind: ForceGraphNodeKind, colors: RelationshipGraphColors): Color = when (kind) {
    ForceGraphNodeKind.FOCUS -> colors.focusNode
    ForceGraphNodeKind.CONTACT -> colors.contactNode
    ForceGraphNodeKind.WORK -> colors.workNode
}

private fun nodeContentColor(kind: ForceGraphNodeKind, colors: RelationshipGraphColors): Color = when (kind) {
    ForceGraphNodeKind.FOCUS -> colors.onFocusNode
    ForceGraphNodeKind.CONTACT -> colors.onContactNode
    ForceGraphNodeKind.WORK -> colors.onWorkNode
}

private fun nodeKindLabel(kind: ForceGraphNodeKind): String = when (kind) {
    ForceGraphNodeKind.FOCUS -> "当前焦点"
    ForceGraphNodeKind.CONTACT -> "联系人"
    ForceGraphNodeKind.WORK -> "工作关系"
}

@Composable
private fun ForceNodeHeader(node: ForceGraphNode, graphColors: RelationshipGraphColors, onDismiss: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(nodeColor(node.kind, graphColors)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.Person,
                contentDescription = null,
                tint = nodeContentColor(node.kind, graphColors),
            )
        }
        Spacer(Modifier.width(ZhiBanSpacing.Md))
        Column(Modifier.weight(1f)) {
            Text(node.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            val subtitle = listOfNotNull(node.title, node.company).distinct().joinToString(" · ")
            Text(
                subtitle.ifBlank { nodeKindLabel(node.kind) },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onDismiss, modifier = Modifier.size(ZhiBanSize.TouchTarget)) {
            Icon(Icons.Rounded.Close, contentDescription = "关闭详情")
        }
    }
}

@Composable
private fun ForceNodeRelatedList(node: ForceGraphNode, model: ForceGraphModel, graphColors: RelationshipGraphColors, onRelatedClick: (String) -> Unit) {
    val relatedLinks = model.links.filter { it.fromId == node.id || it.toId == node.id }
    if (relatedLinks.isEmpty()) {
        Text(
            "暂无可展示的关联",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        relatedLinks.take(12).forEachIndexed { index, link ->
            val relatedId = if (link.fromId == node.id) link.toId else link.fromId
            val related = model.nodes.firstOrNull { it.id == relatedId } ?: return@forEachIndexed
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 56.dp)
                    .clickable { onRelatedClick(relatedId) }
                    .padding(vertical = ZhiBanSpacing.Sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(nodeColor(related.kind, graphColors)))
                Spacer(Modifier.width(ZhiBanSpacing.Md))
                Column(Modifier.weight(1f)) {
                    Text(
                        related.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        buildString {
                            append(graphRelationLabel(link.relationType, link.isHistorical))
                            link.evidenceLabel?.let { append(" · 依据：").append(it) }
                            if (link.isInferred) append(" · 智能推测")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "查看",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
