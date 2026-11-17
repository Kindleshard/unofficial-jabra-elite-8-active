package io.github.kindleshard.elite8active.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Hearing
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.SettingsVoice
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import io.github.kindleshard.elite8active.EarbudsActions
import io.github.kindleshard.elite8active.EarbudsState
import io.github.kindleshard.elite8active.R
import io.github.kindleshard.elite8active.firmware.FirmwareCheck
import io.github.kindleshard.elite8active.gnp.Choice
import io.github.kindleshard.elite8active.gnp.Jabra
import io.github.kindleshard.elite8active.gnp.Toggle

@Composable
private fun ToggleRow(state: EarbudsState, actions: EarbudsActions, t: Toggle, subtitle: String, icon: ImageVector) = SwitchItem(
    t.label, subtitle, state.toggles[t], { actions.setToggle(t, it) },
    icon = icon, enabled = !t.needsWearing || state.wearingDetection,
)

@Composable
private fun ChoiceRow(state: EarbudsState, actions: EarbudsActions, c: Choice, icon: ImageVector) =
    ChoiceItem(c.label, c.options.map { it.first }, state.choices[c], { actions.setChoice(c, it) }, icon = icon)

/** The call settings, shared by Settings and the during-a-call card on Home. */
@Composable
fun CallSettings(state: EarbudsState, actions: EarbudsActions) {
    ToggleRow(state, actions, Toggle.SIDETONE, "Hear your own voice during calls", Icons.Rounded.RecordVoiceOver)
    AnimatedVisibility(state.toggles[Toggle.SIDETONE] == true) {
        StepSlider(
            "Sidetone volume", Jabra.SIDETONE_LEVELS.size,
            state.sidetoneLevel?.let { Jabra.SIDETONE_LEVELS.indexOf(it) }?.takeIf { it >= 0 },
            { actions.setSidetoneLevel(Jabra.SIDETONE_LEVELS[it]) },
            low = "Quieter", high = "Louder",
        )
    }
    ToggleRow(state, actions, Toggle.MUTE_REMINDER, "A tone while the microphone is muted", Icons.Rounded.Notifications)
    ToggleRow(state, actions, Toggle.AUTO_VOLUME, "Louder calls in noisy places", Icons.AutoMirrored.Rounded.VolumeUp)
    ChoiceRow(state, actions, Choice.IN_CALL_EQ, Icons.Rounded.Tune)
}

@Composable
fun SettingsTab(state: EarbudsState, actions: EarbudsActions) = TabPage {
    @Composable
    fun toggle(t: Toggle, subtitle: String, icon: ImageVector) = ToggleRow(state, actions, t, subtitle, icon)

    @Composable
    fun choice(c: Choice, icon: ImageVector) = ChoiceRow(state, actions, c, icon)

    SectionTitle("Wearing detection")
    SurfaceCard {
        toggle(Toggle.WEARING_DETECTION, "Detect when the earbuds are in your ears", Icons.Rounded.Sensors)
        toggle(Toggle.AUTO_PAUSE, "Pause when you take an earbud out", Icons.Rounded.PauseCircle)
        toggle(Toggle.AUTO_ANSWER, "Answer calls by putting an earbud in", Icons.Rounded.Call)
        toggle(Toggle.AUTO_MUTE, "Mute the mic when you take an earbud out", Icons.Rounded.MicOff)
        toggle(Toggle.AUTO_HEARTHROUGH, "Use HearThrough when wearing one earbud", Icons.Rounded.Hearing)
        if (!state.wearingDetection) Hint("Turn on wearing detection to use these.")
    }

    SectionTitle("Calls")
    SurfaceCard {
        CallSettings(state, actions)
        CallQuickAccessItem()
    }

    SectionTitle("General")
    SurfaceCard {
        choice(Choice.HEARTHROUGH_AUDIO, Icons.Rounded.Hearing)
        choice(Choice.SLEEP_MODE, Icons.Rounded.Bedtime)
        choice(Choice.GUIDANCE, Icons.Rounded.SettingsVoice)
        toggle(Toggle.REDUCE_WIND, "Less wind noise outdoors", Icons.Rounded.Air)
    }

    SectionTitle("Automatic modes")
    SurfaceCard { AutoModesItem() }

    SectionTitle("Notifications")
    SurfaceCard { NotificationItems() }

    SectionTitle("Shortcuts")
    SurfaceCard {
        QuickTileItem()
        Hint("Long-press the app icon for ANC, HearThrough and Off. They can be pinned to the home screen or used in Modes and Routines.")
    }

    SectionTitle("Experimental")
    SurfaceCard { ExperimentalItems(actions) }

    SectionTitle("Help")
    SurfaceCard { HelpLinks() }

    SectionTitle("About")
    SurfaceCard {
        var renaming by remember { mutableStateOf(false) }
        val context = LocalContext.current
        val version = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
        ListItem(
            headlineContent = { Text("Name") },
            supportingContent = { Text(state.name ?: "–") },
            leadingContent = { Icon(Icons.Rounded.Edit, null) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable(enabled = state.name != null) { renaming = true },
        )
        ListItem(
            headlineContent = { Text("Firmware") },
            supportingContent = { Text(firmwareStatus(state.firmware, FirmwareCheck.latestKnown(context))) },
            leadingContent = { Icon(Icons.Rounded.Info, null) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
        ListItem(
            headlineContent = { Text("${context.getString(R.string.app_name)} $version") },
            supportingContent = { Text("Free and open source. Not made by or affiliated with Jabra.") },
            leadingContent = { Icon(Icons.Rounded.Headphones, null) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
        if (renaming) {
            RenameDialog(state.name.orEmpty(), onDismiss = { renaming = false }) {
                renaming = false
                actions.rename(it)
            }
        }
    }
}

/** The installed firmware, and how it compares with the newest Jabra lists (once a check has run). */
private fun firmwareStatus(installed: String?, latest: String?) = when {
    installed == null -> "–"
    latest == null -> installed
    FirmwareCheck.isNewer(latest, installed) -> "$installed · $latest is available in Jabra Sound+"
    else -> "$installed · the latest"
}
