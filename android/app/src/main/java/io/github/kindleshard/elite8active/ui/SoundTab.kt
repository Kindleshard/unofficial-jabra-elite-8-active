package io.github.kindleshard.elite8active.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SurroundSound
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kindleshard.elite8active.EarbudsActions
import io.github.kindleshard.elite8active.EarbudsState
import io.github.kindleshard.elite8active.gnp.EqPreset
import io.github.kindleshard.elite8active.gnp.Jabra
import io.github.kindleshard.elite8active.gnp.Toggle
import io.github.kindleshard.elite8active.store.EqLibrary

@Composable
fun SoundTab(state: EarbudsState, actions: EarbudsActions) = TabPage {
    val context = LocalContext.current
    val gains = state.eqGains
    val saved by EqLibrary.items.collectAsStateWithLifecycle()
    // What the curve was before the first custom change, so Reset can go back to it.
    var before by remember { mutableStateOf<List<Int>?>(null) }
    var local by remember(gains) { mutableStateOf(gains) }
    var saving by remember { mutableStateOf(false) }
    val custom = gains != null && state.eqPreset == null
    val savedMatch = saved.firstOrNull { it.gains == gains }
    SurfaceCard {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 40.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Equalizer", style = MaterialTheme.typography.titleMedium)
                    Text(
                        state.eqPreset?.label ?: savedMatch?.name ?: if (custom) "Custom" else "",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (custom) {
                    TextButton(onClick = {
                        actions.setEqGains(before ?: EqPreset.NEUTRAL.gains)
                        before = null
                    }) {
                        Icon(Icons.Rounded.Restore, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Reset")
                    }
                }
                if (custom && savedMatch == null) {
                    TextButton(onClick = { saving = true }) {
                        Icon(Icons.Rounded.BookmarkAdd, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Save")
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            EditableEqCurve(
                local ?: EqPreset.NEUTRAL.gains,
                onDrag = { band, gain ->
                    val current = local ?: return@EditableEqCurve
                    if (before == null) before = current
                    local = current.toMutableList().also { it[band] = gain }
                },
                onDragEnd = { local?.let { if (it != gains) actions.setEqGains(it) } },
                enabled = gains != null,
            )
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Jabra.EQ_BAND_LABELS.forEachIndexed { i, label ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            local?.getOrNull(i)?.let { "%+.1f".format(it / 100f) } ?: "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }

    SectionTitle("Presets")
    PresetGrid(state.eqPreset, enabled = gains != null, onSelect = {
        before = null
        actions.setEq(it)
    })

    if (saved.isNotEmpty()) {
        SectionTitle("My presets")
        SavedEqGrid(saved, gains, enabled = gains != null) {
            before = null
            actions.setEqGains(it.gains)
        }
        Hint("Long-press one to delete it.")
    }
    if (saving) {
        SaveEqDialog(onDismiss = { saving = false }) { name ->
            gains?.let { EqLibrary.save(context, name, it) }
            saving = false
        }
    }

    SectionTitle("Spatial audio")
    SurfaceCard {
        SwitchItem(
            "Spatial Sound", "Wider, room-like sound for music and video",
            state.toggles[Toggle.SPATIAL_SOUND], { actions.setToggle(Toggle.SPATIAL_SOUND, it) },
            icon = Icons.Rounded.SurroundSound,
        )
    }
}
