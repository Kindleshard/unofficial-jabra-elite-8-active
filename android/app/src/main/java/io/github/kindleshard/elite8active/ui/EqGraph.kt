package io.github.kindleshard.elite8active.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.kindleshard.elite8active.gnp.Jabra
import kotlin.math.roundToInt

/** Smooth EQ curve through the band gains; animates between curves. */
@Composable
fun EqCurve(
    gains: List<Int>,
    modifier: Modifier = Modifier,
    height: Dp = 140.dp,
    showPoints: Boolean = true,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val animated = gains.map { animateFloatAsState(it.toFloat(), label = "eq").value }
    val line = color
    val axis = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier.fillMaxWidth().height(height)) {
        if (animated.size < 2) return@Canvas
        val inset = if (showPoints) 10.dp.toPx() else 2.dp.toPx()
        val mid = size.height / 2
        val span = size.height / 2 - inset
        val step = (size.width - 2 * inset) / (animated.size - 1)
        val points = animated.mapIndexed { i, g -> Offset(inset + i * step, mid - g / Jabra.EQ_GAIN_MAX * span) }
        drawLine(axis, Offset(0f, mid), Offset(size.width, mid), strokeWidth = 1.dp.toPx())
        val curve = Path().apply {
            moveTo(points[0].x, points[0].y)
            for (i in 1 until points.size) {
                val a = points[i - 1]
                val b = points[i]
                val cx = (a.x + b.x) / 2
                cubicTo(cx, a.y, cx, b.y, b.x, b.y)
            }
        }
        // Shade between the curve and the 0 line, so boosts and cuts stand out and flat is empty.
        val fill = Path().apply {
            addPath(curve)
            lineTo(points.last().x, mid)
            lineTo(points.first().x, mid)
            close()
        }
        drawPath(fill, line.copy(alpha = 0.18f))
        drawPath(curve, line, style = Stroke(width = (if (showPoints) 3 else 2).dp.toPx(), cap = StrokeCap.Round))
        if (showPoints) points.forEach { drawCircle(line, radius = 5.dp.toPx(), center = it) }
    }
}

/**
 * The EQ curve as an editor: drag any band's point up or down. [onDrag] gets (band, gain) while
 * the finger moves; [onDragEnd] is the moment to write it to the earbuds.
 */
@Composable
fun EditableEqCurve(
    gains: List<Int>,
    onDrag: (Int, Int) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 180.dp,
    enabled: Boolean = true,
) {
    val line = MaterialTheme.colorScheme.primary
    val axis = MaterialTheme.colorScheme.outlineVariant
    val ring = MaterialTheme.colorScheme.surfaceContainer
    var active by remember { mutableIntStateOf(-1) }
    val drag by rememberUpdatedState(onDrag)
    val end by rememberUpdatedState(onDragEnd)
    val latest by rememberUpdatedState(gains)
    val count = gains.size
    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .semantics { contentDescription = "Equalizer graph" }
            .pointerInput(count, enabled) {
                if (!enabled || count < 2) return@pointerInput
                val inset = 14.dp.toPx()
                val mid = size.height / 2f
                val span = size.height / 2f - inset
                val step = (size.width - 2 * inset) / (count - 1)
                fun pointAt(band: Int) = Offset(inset + band * step, mid - latest[band].toFloat() / Jabra.EQ_GAIN_MAX * span)
                fun gainAt(y: Float) = ((mid - y) / span * Jabra.EQ_GAIN_MAX).roundToInt().coerceIn(Jabra.EQ_GAIN_MIN, Jabra.EQ_GAIN_MAX)
                // Only a touch that starts on a point edits it; anywhere else, the page scrolls as usual.
                val grabX = 26.dp.toPx()
                val grabY = 40.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val band = (0 until count).minBy { kotlin.math.abs(pointAt(it).x - down.position.x) }
                    val point = pointAt(band)
                    if (kotlin.math.abs(point.x - down.position.x) > grabX || kotlin.math.abs(point.y - down.position.y) > grabY) {
                        return@awaitEachGesture
                    }
                    down.consume()
                    active = band
                    // Keep the finger's offset from the point, so grabbing doesn't make it jump.
                    val offsetY = point.y - down.position.y
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        change.consume()
                        drag(band, gainAt(change.position.y + offsetY))
                    }
                    active = -1
                    end()
                }
            },
    ) {
        if (count < 2) return@Canvas
        val inset = 14.dp.toPx()
        val mid = size.height / 2
        val span = size.height / 2 - inset
        val step = (size.width - 2 * inset) / (count - 1)
        val points = gains.mapIndexed { i, g -> Offset(inset + i * step, mid - g.toFloat() / Jabra.EQ_GAIN_MAX * span) }
        drawLine(axis, Offset(0f, mid), Offset(size.width, mid), strokeWidth = 1.dp.toPx())
        // Guide lines for each band, so it's clear where to grab.
        points.forEach { drawLine(axis.copy(alpha = 0.5f), Offset(it.x, inset), Offset(it.x, size.height - inset), strokeWidth = 1.dp.toPx()) }
        val curve = Path().apply {
            moveTo(points[0].x, points[0].y)
            for (i in 1 until points.size) {
                val a = points[i - 1]
                val b = points[i]
                val cx = (a.x + b.x) / 2
                cubicTo(cx, a.y, cx, b.y, b.x, b.y)
            }
        }
        val fill = Path().apply {
            addPath(curve)
            lineTo(points.last().x, mid)
            lineTo(points.first().x, mid)
            close()
        }
        drawPath(fill, line.copy(alpha = 0.18f))
        drawPath(curve, line, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
        points.forEachIndexed { i, p ->
            val r = if (i == active) 11.dp.toPx() else 8.dp.toPx()
            drawCircle(line, radius = r, center = p)
            drawCircle(ring, radius = r - 3.dp.toPx(), center = p)
        }
    }
}
