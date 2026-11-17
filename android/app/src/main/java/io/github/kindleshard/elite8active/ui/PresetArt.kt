package io.github.kindleshard.elite8active.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kindleshard.elite8active.gnp.EqPreset
import io.github.kindleshard.elite8active.ui.theme.Navy
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * The EQ presets as illustrated tiles: each has its own colours, a typeface that suits it, and a
 * small piece of art. The art is drawn on a 112×84 grid and scaled to the tile; the selected
 * tile's art moves gently.
 */
private data class PresetLook(val from: Color, val to: Color, val glow: Color, val style: TextStyle)

private fun presetLook(preset: EqPreset) = when (preset) {
    EqPreset.NEUTRAL -> PresetLook(Color(0xFF47587E), Color(0xFF1C2440), Color(0xFF9FB4E8), TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp))
    EqPreset.BASS_BOOST -> PresetLook(Color(0xFF7B2FF2), Color(0xFF2B0A73), Color(0xFFC084FC), TextStyle(fontWeight = FontWeight.Black, fontSize = 14.sp))
    EqPreset.SMOOTH -> PresetLook(
        Color(0xFF14A3A3), Color(0xFF063C4D), Color(0xFF5EEAD4),
        TextStyle(fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, fontSize = 16.sp),
    )
    EqPreset.SPEECH -> PresetLook(
        Color(0xFF37A055), Color(0xFF0D3A1E), Color(0xFF86EFAC),
        TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 13.sp),
    )
    EqPreset.TREBLE_BOOST -> PresetLook(
        Color(0xFF2F86F0), Color(0xFF0B2C70), Color(0xFF7DD3FC),
        TextStyle(fontWeight = FontWeight.Light, fontSize = 14.sp, letterSpacing = 0.3.sp),
    )
    EqPreset.ENERGIZE -> PresetLook(
        Color(0xFFFF6A2B), Color(0xFFA5123F), Color(0xFFFDBA74),
        TextStyle(fontWeight = FontWeight.ExtraBold, fontStyle = FontStyle.Italic, fontSize = 14.sp),
    )
}

@Composable
fun PresetTile(preset: EqPreset, selected: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val look = presetLook(preset)
    val loop by rememberInfiniteTransition(label = "preset").animateFloat(
        0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart), label = "phase",
    )
    // Only the selected tile moves; the others hold their resting pose.
    val t = if (selected) loop else 0f
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(18.dp),
        color = Color.Transparent,
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = modifier.aspectRatio(4f / 3f),
    ) {
        Box(Modifier.fillMaxSize().drawBehind { drawPresetArt(preset, look, t, selected) }) {
            Text(
                preset.label,
                Modifier.align(Alignment.TopStart).padding(start = 11.dp, top = 9.dp, end = 6.dp),
                style = look.style.copy(shadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = 0.35f), blurRadius = 6f)),
                color = Color.White,
                maxLines = 1,
                softWrap = false,
            )
            if (selected) {
                Box(
                    Modifier.align(Alignment.BottomStart).padding(8.dp).size(18.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Check, "Selected", Modifier.size(13.dp), tint = Navy) }
            }
        }
    }
}

private const val W = 112f
private const val H = 84f
private val White = Color.White

private fun DrawScope.drawPresetArt(preset: EqPreset, look: PresetLook, t: Float, selected: Boolean) {
    val k = size.width / W
    scale(k, k, pivot = Offset.Zero) {
        drawRect(Brush.linearGradient(listOf(look.from, look.to), start = Offset.Zero, end = Offset(W, H)), size = androidx.compose.ui.geometry.Size(W, H))
        drawRect(
            Brush.radialGradient(listOf(look.glow.copy(alpha = 0.5f), Color.Transparent), center = Offset(W * 0.85f, H * 0.1f), radius = W * 0.8f),
            size = androidx.compose.ui.geometry.Size(W, H),
        )
        val wave = sin(2 * PI * t).toFloat()
        when (preset) {
            EqPreset.NEUTRAL -> {
                // Three level sliders; on the selected tile the knobs drift a little.
                listOf(40f, 54f, 68f).forEach { y -> drawLine(White.copy(alpha = 0.28f), Offset(52f, y), Offset(100f, y), 5f, StrokeCap.Round) }
                listOf(Triple(66f, 40f, 0.0), Triple(86f, 54f, 2.1), Triple(74f, 68f, 4.2)).forEach { (x, y, phase) ->
                    val dx = if (selected) 6f * (sin(2 * PI * t + phase) - sin(phase)).toFloat() else 0f
                    drawCircle(White.copy(alpha = 0.92f), 5f, Offset(x + dx, y))
                }
            }
            EqPreset.BASS_BOOST -> {
                // A speaker cone; it pulses on the selected tile.
                val c = Offset(96f, 66f)
                val p = 1f + 0.06f * wave
                drawCircle(White.copy(alpha = 0.10f), 44f * p, c, style = Stroke(10f))
                drawCircle(White.copy(alpha = 0.22f), 30f * p, c, style = Stroke(7f))
                drawCircle(White.copy(alpha = 0.45f), 17f * p, c, style = Stroke(5f))
                drawCircle(White.copy(alpha = 0.92f), 7f, c)
            }
            EqPreset.SMOOTH -> {
                // Three silk waves drifting sideways.
                listOf(Triple(52f, 0.9f, 2.6f), Triple(60f, 0.45f, 2.2f), Triple(68f, 0.22f, 2f)).forEachIndexed { i, (base, alpha, width) ->
                    val path = Path()
                    var x = -4f
                    while (x <= W + 4) {
                        val y = base + (8f - i) * sin(2 * PI * (x / 90f - t) + i * 0.6).toFloat()
                        if (x == -4f) path.moveTo(x, y) else path.lineTo(x, y)
                        x += 2f
                    }
                    drawPath(path, White.copy(alpha = alpha), style = Stroke(width, cap = StrokeCap.Round))
                }
            }
            EqPreset.SPEECH -> {
                // A voice recording's bars; they "talk" on the selected tile.
                val heights = listOf(6f, 12f, 20f, 30f, 18f, 26f, 14f, 22f, 10f, 6f)
                heights.forEachIndexed { i, h0 ->
                    val h = if (selected) h0 * (0.55f + 0.45f * abs(sin(2 * PI * t * 2 + i * 0.9)).toFloat()) else h0
                    val x = 46f + i * 6f
                    drawLine(White.copy(alpha = 0.35f + 0.65f * (h0 / 30f)), Offset(x, 56f - h / 2), Offset(x, 56f + h / 2), 4f, StrokeCap.Round)
                }
            }
            EqPreset.TREBLE_BOOST -> {
                // Sparkles; they twinkle on the selected tile.
                listOf(Triple(88f, 56f, 9f), Triple(68f, 66f, 5f), Triple(102f, 36f, 4f), Triple(74f, 44f, 3f)).forEachIndexed { i, (x, y, r) ->
                    val tw = if (selected) 1f + 0.18f * sin(2 * PI * t + i * 1.7).toFloat() else 1f
                    drawPath(sparkle(x, y, r * tw), White.copy(alpha = if (r > 6) 0.95f else 0.6f))
                }
            }
            EqPreset.ENERGIZE -> {
                // A lightning bolt with a glow that breathes on the selected tile.
                val bolt = Path().apply {
                    moveTo(86f, 4f); lineTo(62f, 44f); lineTo(80f, 44f); lineTo(70f, 80f); lineTo(104f, 34f); lineTo(84f, 34f); lineTo(98f, 4f); close()
                }
                val glow = if (selected) 0.5f + 0.3f * wave else 0.45f
                drawPath(bolt, Color(0xFFFFE7A3).copy(alpha = glow * 0.35f), style = Stroke(12f))
                drawPath(bolt, Color(0xFFFFE7A3).copy(alpha = glow * 0.6f), style = Stroke(6f))
                drawPath(bolt, White.copy(alpha = 0.92f))
            }
        }
        // A soft sheen across the top half.
        drawRect(
            Brush.verticalGradient(0f to White.copy(alpha = 0.12f), 0.5f to Color.Transparent, endY = H),
            size = androidx.compose.ui.geometry.Size(W, H),
        )
    }
}

/** A four-point sparkle centred on (x, y). */
private fun sparkle(x: Float, y: Float, r: Float) = Path().apply {
    val a = r * 1.6f
    val b = r * 0.25f
    moveTo(x, y - a)
    cubicTo(x + b, y - b, x + b, y - b, x + a, y)
    cubicTo(x + b, y + b, x + b, y + b, x, y + a)
    cubicTo(x - b, y + b, x - b, y + b, x - a, y)
    cubicTo(x - b, y - b, x - b, y - b, x, y - a)
    close()
}
