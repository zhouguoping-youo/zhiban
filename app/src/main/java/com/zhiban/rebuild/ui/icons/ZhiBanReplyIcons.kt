package com.zhiban.rebuild.ui.icons

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

internal enum class ReplyGlyph {
    COPY,
    POSITIVE,
    NEGATIVE,
    SPEAK,
    SHARE,
}

/** Compact, single-stroke reply glyphs. Touch size is owned by the surrounding IconButton. */
@Composable
internal fun ZhiBanReplyIcon(glyph: ReplyGlyph, label: String, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.semantics { contentDescription = label }) {
        val stroke = Stroke(
            width = 1.6.dp.toPx(),
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )
        when (glyph) {
            ReplyGlyph.COPY -> drawCopyGlyph(color, stroke)
            ReplyGlyph.POSITIVE -> drawThumb(color, stroke, up = true)

            ReplyGlyph.NEGATIVE -> drawThumb(color, stroke, up = false)

            ReplyGlyph.SPEAK -> drawSpeakGlyph(color, stroke)

            ReplyGlyph.SHARE -> {
                drawLine(
                    color,
                    Offset(size.width * .50f, size.height * .18f),
                    Offset(size.width * .50f, size.height * .63f),
                    stroke.width,
                    StrokeCap.Round,
                )
                drawLine(
                    color,
                    Offset(size.width * .50f, size.height * .18f),
                    Offset(size.width * .34f, size.height * .35f),
                    stroke.width,
                    StrokeCap.Round,
                )
                drawLine(
                    color,
                    Offset(size.width * .50f, size.height * .18f),
                    Offset(size.width * .66f, size.height * .35f),
                    stroke.width,
                    StrokeCap.Round,
                )
                val tray = Path().apply {
                    moveTo(size.width * .25f, size.height * .52f)
                    lineTo(size.width * .20f, size.height * .52f)
                    lineTo(size.width * .20f, size.height * .82f)
                    lineTo(size.width * .80f, size.height * .82f)
                    lineTo(size.width * .80f, size.height * .52f)
                    lineTo(size.width * .75f, size.height * .52f)
                }
                drawPath(tray, color, style = stroke)
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawThumb(color: Color, stroke: Stroke, up: Boolean) {
    fun y(value: Float): Float = size.height * if (up) value else 1f - value
    val thumb = Path().apply {
        moveTo(size.width * .30f, y(.48f))
        lineTo(size.width * .43f, y(.48f))
        lineTo(size.width * .54f, y(.23f))
        cubicTo(size.width * .58f, y(.14f), size.width * .69f, y(.18f), size.width * .67f, y(.30f))
        lineTo(size.width * .64f, y(.42f))
        lineTo(size.width * .80f, y(.42f))
        cubicTo(size.width * .86f, y(.42f), size.width * .89f, y(.48f), size.width * .87f, y(.55f))
        lineTo(size.width * .78f, y(.78f))
        cubicTo(size.width * .76f, y(.84f), size.width * .71f, y(.86f), size.width * .65f, y(.86f))
        lineTo(size.width * .43f, y(.86f))
        lineTo(size.width * .30f, y(.80f))
        close()
    }
    drawPath(thumb, color, style = stroke)
    drawRoundRect(
        color = color,
        topLeft = Offset(size.width * .14f, minOf(y(.48f), y(.86f))),
        size = Size(size.width * .16f, kotlin.math.abs(y(.86f) - y(.48f))),
        cornerRadius = CornerRadius(1.3.dp.toPx()),
        style = stroke,
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCopyGlyph(color: Color, stroke: Stroke) {
val radius = CornerRadius(1.8.dp.toPx())
drawRoundRect(
    color = color,
    topLeft = Offset(size.width * .28f, size.height * .14f),
    size = Size(size.width * .58f, size.height * .58f),
    cornerRadius = radius,
    style = stroke,
)
drawRoundRect(
    color = color,
    topLeft = Offset(size.width * .14f, size.height * .28f),
    size = Size(size.width * .58f, size.height * .58f),
    cornerRadius = radius,
    style = stroke,
)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSpeakGlyph(color: Color, stroke: Stroke) {
val speaker = Path().apply {
    moveTo(size.width * .16f, size.height * .42f)
    lineTo(size.width * .34f, size.height * .42f)
    lineTo(size.width * .52f, size.height * .25f)
    lineTo(size.width * .52f, size.height * .75f)
    lineTo(size.width * .34f, size.height * .58f)
    lineTo(size.width * .16f, size.height * .58f)
    close()
}
drawPath(speaker, color, style = stroke)
drawArc(
    color = color,
    startAngle = -48f,
    sweepAngle = 96f,
    useCenter = false,
    topLeft = Offset(size.width * .42f, size.height * .30f),
    size = Size(size.width * .30f, size.height * .40f),
    style = stroke,
)
drawArc(
    color = color,
    startAngle = -45f,
    sweepAngle = 90f,
    useCenter = false,
    topLeft = Offset(size.width * .42f, size.height * .18f),
    size = Size(size.width * .48f, size.height * .64f),
    style = stroke,
)
}


