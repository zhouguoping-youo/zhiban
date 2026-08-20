package com.zhiban.rebuild.ui.icons

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zhiban.rebuild.ui.theme.CloudBlue
import com.zhiban.rebuild.ui.theme.DeepNavy
import com.zhiban.rebuild.ui.theme.ZhiBanTerracotta
import kotlin.math.cos
import kotlin.math.sin

// 保留旧调用名，但品牌图形只使用唯一主橙，不再制造第二个橙色端点。
val BrandGradient: Brush = SolidColor(ZhiBanTerracotta)
val DeepNavyGradient = Brush.linearGradient(listOf(DeepNavy, Color(0xFF2D2D44)))

// ═══════════════ LightningBoltIcon ═══════════════
@Composable
fun LightningBoltIcon(modifier: Modifier = Modifier.size(24.dp), color: Color = LocalContentColor.current, selected: Boolean = false) {
    val drawColor = color
    Canvas(modifier = modifier) {
        val path = Path().apply {
            moveTo(size.width * 13f / 24f, size.height * 2f / 24f)
            lineTo(size.width * 3f / 24f, size.height * 14f / 24f)
            lineTo(size.width * 12f / 24f, size.height * 14f / 24f)
            lineTo(size.width * 11f / 24f, size.height * 22f / 24f)
            lineTo(size.width * 21f / 24f, size.height * 10f / 24f)
            lineTo(size.width * 12f / 24f, size.height * 10f / 24f)
            close()
        }
        drawPath(path = path, color = drawColor)
    }
}

// ═══════════════ AskIcon ═══════════════
@Composable
fun AskIcon(modifier: Modifier = Modifier.size(24.dp), color: Color = LocalContentColor.current, selected: Boolean = false) {
    Canvas(modifier = modifier) {
        val stroke = Stroke(
            width = (if (selected) 2.2.dp else 1.8.dp).toPx(),
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )
        val bubble = Path().apply {
            moveTo(size.width * 0.18f, size.height * 0.2f)
            quadraticTo(size.width * 0.12f, size.height * 0.2f, size.width * 0.12f, size.height * 0.3f)
            lineTo(size.width * 0.12f, size.height * 0.67f)
            quadraticTo(size.width * 0.12f, size.height * 0.76f, size.width * 0.22f, size.height * 0.76f)
            lineTo(size.width * 0.34f, size.height * 0.76f)
            lineTo(size.width * 0.25f, size.height * 0.9f)
            lineTo(size.width * 0.48f, size.height * 0.76f)
            lineTo(size.width * 0.82f, size.height * 0.76f)
            quadraticTo(size.width * 0.88f, size.height * 0.76f, size.width * 0.88f, size.height * 0.67f)
            lineTo(size.width * 0.88f, size.height * 0.3f)
            quadraticTo(size.width * 0.88f, size.height * 0.2f, size.width * 0.78f, size.height * 0.2f)
            close()
        }
        drawPath(bubble, color = color, style = stroke)
        drawLine(
            color = color,
            start = Offset(size.width * 0.3f, size.height * 0.42f),
            end = Offset(size.width * 0.7f, size.height * 0.42f),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = Offset(size.width * 0.3f, size.height * 0.57f),
            end = Offset(size.width * 0.57f, size.height * 0.57f),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round,
        )
    }
}

// ═══════════════ CalendarIcon ═══════════════
@Composable
fun CalendarIcon(modifier: Modifier = Modifier.size(24.dp), color: Color = LocalContentColor.current, selected: Boolean = false) {
    val strokeColor = color
    Canvas(modifier = modifier) {
        val strokeWidth = (if (selected) 2.2.dp else 1.8.dp).toPx()
        val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawRoundRect(
            color = strokeColor,
            size = size.copy(height = size.height * 0.85f),
            cornerRadius = CornerRadius(3.dp.toPx()),
            style = stroke,
        )
        drawLine(
            color = strokeColor,
            start = Offset(size.width * 0.15f, size.height * 0.18f),
            end = Offset(size.width * 0.85f, size.height * 0.18f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = strokeColor,
            start = Offset(size.width * 0.35f, size.height * 0.08f),
            end = Offset(size.width * 0.35f, size.height * 0.22f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = strokeColor,
            start = Offset(size.width * 0.65f, size.height * 0.08f),
            end = Offset(size.width * 0.65f, size.height * 0.22f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
        val dotRadius = 1.5.dp.toPx()
        val startX =
            size.width * 0.28f
        val startY =
            size.height * 0.42f
        val spacingX = size.width * 0.22f
        val spacingY = size.height * 0.18f
        for (row in 0..1) {
            for (col in 0..2) {
                drawCircle(
                    color = strokeColor,
                    radius = dotRadius,
                    center = Offset(startX + col * spacingX, startY + row * spacingY),
                )
            }
        }
    }
}

// ═══════════════ RelationIcon ═══════════════
@Composable
fun RelationIcon(modifier: Modifier = Modifier.size(24.dp), color: Color = LocalContentColor.current, selected: Boolean = false) {
    val strokeColor = color
    Canvas(modifier = modifier) {
        val strokeWidth = (if (selected) 2.2.dp else 1.8.dp).toPx()
        val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val radius = 3.dp.toPx()
        drawCircle(
            color = strokeColor,
            radius = radius,
            center = Offset(size.width * 5f / 24f, size.height * 5f / 24f),
            style = stroke,
        )
        drawCircle(
            color = strokeColor,
            radius = radius,
            center = Offset(size.width * 19f / 24f, size.height * 5f / 24f),
            style = stroke,
        )
        drawCircle(
            color = strokeColor,
            radius = radius,
            center = Offset(size.width * 12f / 24f, size.height * 19f / 24f),
            style = stroke,
        )
        drawLine(
            color = strokeColor,
            start = Offset(size.width * 7.5f / 24f, size.height * 7f / 24f),
            end = Offset(size.width * 16.5f / 24f, size.height * 7f / 24f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = strokeColor,
            start = Offset(size.width * 6.5f / 24f, size.height * 7.5f / 24f),
            end = Offset(size.width * 10f / 24f, size.height * 16.5f / 24f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = strokeColor,
            start = Offset(size.width * 17.5f / 24f, size.height * 7.5f / 24f),
            end = Offset(size.width * 14f / 24f, size.height * 16.5f / 24f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
    }
}

// ═══════════════ TimelineIcon ═══════════════
// 时间线：带节点的上升折线，表达「活动流按时间展开」。
@Composable
fun TimelineIcon(modifier: Modifier = Modifier.size(24.dp), color: Color = LocalContentColor.current, selected: Boolean = false) {
    val strokeColor = color
    Canvas(modifier = modifier) {
        val stroke = Stroke(
            width = (if (selected) 2.2.dp else 1.8.dp).toPx(),
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )
        val path = Path().apply {
            moveTo(size.width * 3f / 24f, size.height * 18f / 24f)
            lineTo(size.width * 9f / 24f, size.height * 12f / 24f)
            lineTo(size.width * 13f / 24f, size.height * 16f / 24f)
            lineTo(size.width * 19f / 24f, size.height * 8f / 24f)
            lineTo(size.width * 21.5f / 24f, size.height * 10f / 24f)
        }
        drawPath(path = path, color = strokeColor, style = stroke)
        val dotRadius = (if (selected) 1.7.dp else 1.4.dp).toPx()
        drawCircle(color = strokeColor, radius = dotRadius, center = Offset(size.width * 3f / 24f, size.height * 18f / 24f))
        drawCircle(color = strokeColor, radius = dotRadius, center = Offset(size.width * 9f / 24f, size.height * 12f / 24f))
        drawCircle(color = strokeColor, radius = dotRadius, center = Offset(size.width * 13f / 24f, size.height * 16f / 24f))
        drawCircle(color = strokeColor, radius = dotRadius, center = Offset(size.width * 19f / 24f, size.height * 8f / 24f))
        drawCircle(color = strokeColor, radius = dotRadius, center = Offset(size.width * 21.5f / 24f, size.height * 10f / 24f))
    }
}

// ═══════════════ SkillGridIcon ═══════════════
@Composable
fun SkillGridIcon(modifier: Modifier = Modifier.size(24.dp), color: Color = LocalContentColor.current, selected: Boolean = false) {
    val strokeColor = color
    Canvas(modifier = modifier) {
        val stroke = Stroke(
            width = (if (selected) 2.2.dp else 1.8.dp).toPx(),
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )
        val cornerRadius = CornerRadius(1.5.dp.toPx())
        drawRoundRect(
            color = strokeColor,
            topLeft = Offset(size.width * 3f / 24f, size.height * 3f / 24f),
            size = Size(size.width * 7f / 24f, size.height * 7f / 24f),
            cornerRadius = cornerRadius,
            style = stroke,
        )
        drawRoundRect(
            color = strokeColor,
            topLeft = Offset(size.width * 14f / 24f, size.height * 3f / 24f),
            size = Size(size.width * 7f / 24f, size.height * 7f / 24f),
            cornerRadius = cornerRadius,
            style = stroke,
        )
        drawRoundRect(
            color = strokeColor,
            topLeft = Offset(size.width * 3f / 24f, size.height * 14f / 24f),
            size = Size(size.width * 7f / 24f, size.height * 7f / 24f),
            cornerRadius = cornerRadius,
            style = stroke,
        )
        drawRoundRect(
            color = strokeColor,
            topLeft = Offset(size.width * 14f / 24f, size.height * 14f / 24f),
            size = Size(size.width * 7f / 24f, size.height * 7f / 24f),
            cornerRadius = cornerRadius,
            style = stroke,
        )
    }
}

// ═══════════════ ProfileIcon ═══════════════
@Composable
fun ProfileIcon(modifier: Modifier = Modifier.size(24.dp), color: Color = LocalContentColor.current, selected: Boolean = false) {
    val strokeColor = color
    Canvas(modifier = modifier) {
        val stroke = Stroke(
            width = (if (selected) 2.2.dp else 1.8.dp).toPx(),
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )
        drawCircle(
            color = strokeColor,
            radius = size.width * 0.2f,
            center = Offset(
                size.width * 0.5f,
                size.height * 0.28f,
            ),
            style = stroke,
        )
        val bodyPath = Path().apply {
            moveTo(size.width * 0.18f, size.height * 0.85f)
            quadraticTo(size.width * 0.25f, size.height * 0.5f, size.width * 0.35f, size.height * 0.48f)
            quadraticTo(size.width * 0.65f, size.height * 0.48f, size.width * 0.75f, size.height * 0.5f)
            quadraticTo(size.width * 0.82f, size.height * 0.6f, size.width * 0.82f, size.height * 0.85f)
        }
        drawPath(bodyPath, strokeColor, style = stroke)
    }
}

// ═══════════════ AIAvatarIcon ═══════════════
@Composable
fun AIAvatarIcon(modifier: Modifier = Modifier, iconSize: Dp = 32.dp) {
    Canvas(modifier = modifier.size(iconSize)) {
        drawCircle(brush = BrandGradient)
        val path = Path().apply {
            moveTo(this@Canvas.size.width * 0.6f, this@Canvas.size.height * 0.2f)
            lineTo(this@Canvas.size.width * 0.35f, this@Canvas.size.height * 0.5f)
            lineTo(this@Canvas.size.width * 0.55f, this@Canvas.size.height * 0.5f)
            lineTo(this@Canvas.size.width * 0.3f, this@Canvas.size.height * 0.8f)
            lineTo(this@Canvas.size.width * 0.7f, this@Canvas.size.height * 0.45f)
            lineTo(this@Canvas.size.width * 0.52f, this@Canvas.size.height * 0.45f)
            close()
        }
        drawPath(path, Color.White)
    }
}

@Composable
fun SettingsIcon(modifier: Modifier = Modifier.size(24.dp), color: Color = LocalContentColor.current, selected: Boolean = false) {
    val strokeColor = color
    Canvas(modifier = modifier) {
        val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val cx =
            size.width * 0.5f
        val cy =
            size.height * 0.5f
        val outerR = size.width * 0.38f
        val holeR = size.width * 0.15f
        for (i in 0 until 8) {
            val toothOuterR = size.width * 0.44f
            val toothStartAngle = (i * 360f / 8 - 6f) * (Math.PI / 180f).toFloat()
            val toothEndAngle = (i * 360f / 8 + 6f) * (Math.PI / 180f).toFloat()
            val toothPath = Path().apply {
                moveTo(cx + outerR * cos(toothStartAngle), cy + outerR * sin(toothStartAngle))
                lineTo(cx + toothOuterR * cos(toothStartAngle), cy + toothOuterR * sin(toothStartAngle))
                lineTo(cx + toothOuterR * cos(toothEndAngle), cy + toothOuterR * sin(toothEndAngle))
                lineTo(cx + outerR * cos(toothEndAngle), cy + outerR * sin(toothEndAngle))
            }
            drawPath(toothPath, strokeColor, style = stroke)
        }
        drawCircle(color = strokeColor, radius = outerR, center = Offset(cx, cy), style = stroke)
        drawCircle(color = strokeColor, radius = holeR, center = Offset(cx, cy), style = stroke)
    }
}

@Composable
fun ZhiBanLogoIcon(modifier: Modifier = Modifier, iconSize: Dp = 72.dp) {
    Canvas(modifier = modifier.size(iconSize)) {
        drawCircle(brush = BrandGradient, radius = this.size.minDimension / 2)
        val path = Path().apply {
            moveTo(size.width * 0.58f, size.height * 0.15f)
            lineTo(size.width * 0.32f, size.height * 0.45f)
            lineTo(size.width * 0.52f, size.height * 0.45f)
            lineTo(size.width * 0.28f, size.height * 0.85f)
            lineTo(size.width * 0.72f, size.height * 0.48f)
            lineTo(size.width * 0.55f, size.height * 0.48f)
            close()
        }
        drawPath(path, Color.White)
    }
}
