package io.github.kindleshard.elite8active.ui

import androidx.compose.animation.Animatable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.kindleshard.elite8active.ConnectionStatus
import io.github.kindleshard.elite8active.EarbudsActions
import io.github.kindleshard.elite8active.EarbudsState
import io.github.kindleshard.elite8active.R
import io.github.kindleshard.elite8active.gnp.Side
import io.github.kindleshard.elite8active.ui.theme.Good
import io.github.kindleshard.elite8active.ui.theme.Low
import kotlinx.coroutines.delay

/** How the connection looks on screen: steady, being opened, or lost. */
enum class LinkLook { CONNECTED, CONNECTING, DISCONNECTED }

val EarbudsState.linkLook: LinkLook
    get() = when (status) {
        ConnectionStatus.Connected -> LinkLook.CONNECTED
        ConnectionStatus.Idle, ConnectionStatus.Connecting -> LinkLook.CONNECTING
        ConnectionStatus.Reconnecting, ConnectionStatus.Failed -> LinkLook.DISCONNECTED
    }

/** True for a moment after the connection comes back, so it can be confirmed in green. */
@Composable
fun rememberJustConnected(look: LinkLook): Boolean {
    var previous by remember { mutableStateOf(look) }
    var flash by remember { mutableStateOf(false) }
    LaunchedEffect(look) {
        val back = look == LinkLook.CONNECTED && previous != LinkLook.CONNECTED
        previous = look
        flash = back
        if (back) {
            delay(2_000)
            flash = false
        }
    }
    return flash
}

/**
 * The earbuds on Home: name, connection, and the battery of each part. When the connection drops,
 * the card flashes red and stays tinted until it's back, then flashes green. An earbud that's in
 * the case or out of range is drawn faded and its gauge says so.
 */
@Composable
fun DeviceCard(state: EarbudsState, actions: EarbudsActions) {
    var renaming by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val look = state.linkLook
    val justConnected = rememberJustConnected(look)
    val tint = remember { Animatable(Color.Transparent) }
    val red = scheme.error
    LaunchedEffect(look, justConnected) {
        when {
            look == LinkLook.DISCONNECTED -> {
                tint.animateTo(red.copy(alpha = 0.45f), tween(200))
                tint.animateTo(red.copy(alpha = 0.16f), tween(1_200))
            }
            justConnected -> {
                tint.animateTo(Good.copy(alpha = 0.4f), tween(200))
                tint.animateTo(Good.copy(alpha = 0f), tween(1_200, delayMillis = 500))
            }
            else -> tint.animateTo(tint.value.copy(alpha = 0f), tween(400))
        }
    }
    Surface(shape = MaterialTheme.shapes.extraLarge, color = Color.Transparent, modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .background(Brush.linearGradient(listOf(scheme.primaryContainer.copy(alpha = 0.85f), scheme.surfaceContainer)))
                .drawBehind { drawRect(tint.value) }
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The pair in outline, as they sit in your ears (the logo is already in the title bar).
                Row {
                    Side.entries.forEach { side ->
                        val faded = state.isOffline(side)
                        EarbudIcon(null, mirrored = side == Side.RIGHT, size = 42.dp, color = scheme.primary.copy(alpha = if (faded) 0.3f else 1f))
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(state.name ?: "Jabra earbuds", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    ConnectionLine(state, look, justConnected)
                }
                IconButton(onClick = { renaming = true }, enabled = state.connected) { Icon(Icons.Rounded.Edit, "Rename earbuds") }
            }
            Spacer(Modifier.height(18.dp))
            // Last known values while disconnected, faded.
            Row(
                Modifier.fillMaxWidth().alpha(if (look == LinkLook.CONNECTED) 1f else 0.45f),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                val bud = ImageVector.vectorResource(R.drawable.ic_bud_glyph)
                val battery = state.battery
                BatteryGauge("Left", battery?.left, bud, offline = state.isOffline(Side.LEFT))
                BatteryGauge("Right", battery?.right, bud, mirrorIcon = true, offline = state.isOffline(Side.RIGHT))
                BatteryGauge("Case", battery?.case, EliteIcons.Case)
            }
            AnimatedVisibility(state.status == ConnectionStatus.Failed) { ConnectionHelp(state, actions) }
            Spacer(Modifier.height(4.dp))
            BatteryHealthButton(Modifier.align(Alignment.CenterHorizontally))
        }
    }
    if (renaming) {
        RenameDialog(state.name.orEmpty(), onDismiss = { renaming = false }) {
            renaming = false
            actions.rename(it)
        }
    }
}

@Composable
private fun ConnectionLine(state: EarbudsState, look: LinkLook, justConnected: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val text = when (look) {
        LinkLook.CONNECTED -> if (justConnected) "Connected" else "Connected" + (state.firmware?.let { " · FW $it" } ?: "")
        LinkLook.CONNECTING -> "Connecting…"
        LinkLook.DISCONNECTED -> if (state.status == ConnectionStatus.Reconnecting) "Disconnected · trying again" else "Disconnected"
    }
    val color = when {
        look == LinkLook.DISCONNECTED -> scheme.error
        justConnected -> Good
        else -> scheme.onSurfaceVariant
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusMark(look, if (look == LinkLook.CONNECTED) Good else color)
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

/** Why the connection failed, with a retry; Sound+ holding the channel is the usual cause. */
@Composable
private fun ConnectionHelp(state: EarbudsState, actions: EarbudsActions) {
    val context = LocalContext.current
    Column(Modifier.padding(top = 14.dp)) {
        Text(
            (state.connectFailure ?: "The earbuds can't be reached.") +
                if (state.soundPlusInstalled) " If Jabra Sound+ is running, it may be holding them." else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row {
            TextButton(onClick = actions::connect) { Text("Try again") }
            if (state.soundPlusInstalled) TextButton(onClick = { openSoundPlusInfo(context) }) { Text("Sound+ app info") }
        }
    }
}

/** A dot, or a small spinner while connecting. */
@Composable
private fun StatusMark(look: LinkLook, color: Color) {
    if (look == LinkLook.CONNECTING) {
        CircularProgressIndicator(Modifier.size(10.dp), color = color, strokeWidth = 1.5.dp)
    } else {
        Box(Modifier.size(8.dp).background(color, CircleShape))
    }
}

/**
 * The connection at a glance, for the title bar of the tabs without the device card: shown while
 * connecting or disconnected (tap to retry), and briefly in green once connected again.
 */
@Composable
fun ConnectionPill(state: EarbudsState, onRetry: () -> Unit) {
    val look = state.linkLook
    val justConnected = rememberJustConnected(look)
    AnimatedVisibility(
        look != LinkLook.CONNECTED || justConnected,
        enter = fadeIn() + expandHorizontally(),
        exit = fadeOut() + shrinkHorizontally(),
    ) {
        val color = when (look) {
            LinkLook.CONNECTED -> Good
            LinkLook.CONNECTING -> MaterialTheme.colorScheme.onSurfaceVariant
            LinkLook.DISCONNECTED -> MaterialTheme.colorScheme.error
        }
        Surface(onClick = onRetry, enabled = look == LinkLook.DISCONNECTED, shape = CircleShape, color = color.copy(alpha = 0.14f)) {
            Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusMark(look, color)
                Spacer(Modifier.width(6.dp))
                Text(
                    when (look) {
                        LinkLook.CONNECTED -> "Connected"
                        LinkLook.CONNECTING -> "Connecting"
                        LinkLook.DISCONNECTED -> "Disconnected"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = color,
                )
            }
        }
    }
}

/**
 * A battery readout as a half-circle gauge with the percentage inside and the part's name below.
 * The arc is full at 100 % and empties from its left end towards the right.
 */
@Composable
fun BatteryGauge(label: String, percent: Int?, icon: ImageVector, mirrorIcon: Boolean = false, offline: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    val color = when {
        percent == null -> scheme.outline
        percent <= 15 -> scheme.error
        percent <= 30 -> Low
        else -> Good
    }
    val level by animateFloatAsState((percent ?: 0) / 100f, label = "battery")
    val track = scheme.onSurface.copy(alpha = 0.1f)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(width = 72.dp, height = 40.dp), contentAlignment = Alignment.BottomCenter) {
            Canvas(Modifier.matchParentSize()) {
                val stroke = 6.dp.toPx()
                val topLeft = Offset(stroke / 2, stroke / 2)
                val arc = Size(size.width - stroke, size.width - stroke)
                val style = Stroke(stroke, cap = StrokeCap.Round)
                drawArc(track, 180f, 180f, useCenter = false, topLeft = topLeft, size = arc, style = style)
                if (level > 0f) drawArc(color, 180f + 180f * (1 - level), 180f * level, useCenter = false, topLeft = topLeft, size = arc, style = style)
            }
            Text(
                when {
                    offline -> "Offline"
                    percent != null -> "$percent%"
                    else -> "–"
                },
                style = if (offline) MaterialTheme.typography.labelSmall else MaterialTheme.typography.titleSmall,
                color = if (offline) scheme.onSurfaceVariant else scheme.onSurface,
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(14.dp).graphicsLayer { if (mirrorIcon) scaleX = -1f }, tint = scheme.onSurfaceVariant)
            Spacer(Modifier.width(4.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        }
    }
}
