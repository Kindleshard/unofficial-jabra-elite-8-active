package io.github.kindleshard.elite8active.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SurroundSound
import androidx.compose.material.icons.rounded.Hearing
import androidx.compose.material.icons.rounded.PhoneInTalk
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kindleshard.elite8active.EarbudsActions
import io.github.kindleshard.elite8active.EarbudsState
import io.github.kindleshard.elite8active.R
import io.github.kindleshard.elite8active.gnp.EqPreset
import io.github.kindleshard.elite8active.gnp.Jabra
import io.github.kindleshard.elite8active.gnp.SoundMode
import io.github.kindleshard.elite8active.gnp.Toggle
import io.github.kindleshard.elite8active.store.EqLibrary

@Composable
fun HomeTab(state: EarbudsState, actions: EarbudsActions, inCall: Boolean) = TabPage {
    if (inCall) DuringCallCard(state, actions)
    DeviceCard(state, actions)

    SectionTitle("Noise canceling modes")
    SoundModeTiles(state.mode, actions::setMode)
    AnimatedVisibility(state.mode == SoundMode.HEAR_THROUGH) {
        SurfaceCard {
            StepSlider(
                "How much you hear", Jabra.HEARTHROUGH_STEPS, state.hearThroughLevel, actions::setHearThroughLevel,
                low = "Less", high = "More", icon = Icons.Rounded.Hearing,
            )
        }
    }

    SectionTitle("Equalizer")
    PresetGrid(state.eqPreset, enabled = state.eqGains != null, onSelect = actions::setEq)
    val saved by EqLibrary.items.collectAsStateWithLifecycle()
    if (saved.isNotEmpty()) SavedEqGrid(saved, state.eqGains, enabled = state.eqGains != null) { actions.setEqGains(it.gains) }

    SurfaceCard {
        SwitchItem(
            "Spatial Sound", "Wider, room-like sound for music and video",
            state.toggles[Toggle.SPATIAL_SOUND], { actions.setToggle(Toggle.SPATIAL_SOUND, it) },
            icon = Icons.Rounded.SurroundSound,
        )
    }

    SectionTitle("Find My")
    FindMyCard()
}

/** Shown at the top of Home while a call is going on (the "On a call" notification opens it). */
@Composable
private fun DuringCallCard(state: EarbudsState, actions: EarbudsActions) {
    Card(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(vertical = 6.dp)) {
            ListItem(
                headlineContent = { Text("On a call", style = MaterialTheme.typography.titleMedium) },
                supportingContent = { Text("Changes apply to this call right away") },
                leadingContent = { Icon(Icons.Rounded.PhoneInTalk, null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            CallSettings(state, actions)
        }
    }
}

@Composable
private fun SoundModeTiles(current: SoundMode?, onSelect: (SoundMode) -> Unit) {
    val tiles = listOf(
        Triple(SoundMode.ANC, ImageVector.vectorResource(R.drawable.ic_mode_anc), "Block noise"),
        Triple(SoundMode.HEAR_THROUGH, ImageVector.vectorResource(R.drawable.ic_mode_hearthrough), "Let sound in"),
        Triple(SoundMode.OFF, ImageVector.vectorResource(R.drawable.ic_mode_off), "Natural"),
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        tiles.forEach { (mode, icon, hint) ->
            val selected = current == mode
            val container by animateColorAsState(
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer, label = "tile",
            )
            val content by animateColorAsState(
                if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface, label = "tileText",
            )
            Surface(
                onClick = { onSelect(mode) },
                enabled = current != null,
                shape = MaterialTheme.shapes.large,
                color = container,
                contentColor = content,
                modifier = Modifier.weight(1f).height(100.dp),
            ) {
                Column(Modifier.padding(start = 14.dp, end = 10.dp, top = 14.dp, bottom = 14.dp), verticalArrangement = Arrangement.SpaceBetween) {
                    Icon(icon, null, Modifier.size(30.dp))
                    Column {
                        Text(
                            mode.label,
                            style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp, letterSpacing = 0.sp),
                            maxLines = 1,
                            softWrap = false,
                        )
                        Text(
                            hint,
                            style = MaterialTheme.typography.labelSmall,
                            color = content.copy(alpha = 0.75f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** All six presets as illustrated tiles, three per row (Home and the Sound tab share it). */
@Composable
fun PresetGrid(selected: EqPreset?, enabled: Boolean, onSelect: (EqPreset) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        EqPreset.entries.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { preset ->
                    PresetTile(preset, selected == preset, enabled, { onSelect(preset) }, Modifier.weight(1f))
                }
            }
        }
    }
}
