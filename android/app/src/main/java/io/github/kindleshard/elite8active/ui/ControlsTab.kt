package io.github.kindleshard.elite8active.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Assistant
import androidx.compose.material.icons.rounded.SettingsBackupRestore
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.kindleshard.elite8active.EarbudsActions
import io.github.kindleshard.elite8active.EarbudsState
import io.github.kindleshard.elite8active.gnp.Buttons
import io.github.kindleshard.elite8active.gnp.Choice
import io.github.kindleshard.elite8active.gnp.Jabra
import io.github.kindleshard.elite8active.gnp.Side

// The fourth menu is hidden in Sound+ (see Buttons.MENUS).
private val MENU_TITLES = listOf("Music", "Ringing", "In call", "Dialling")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControlsTab(state: EarbudsState, actions: EarbudsActions) = TabPage {
    var menu by rememberSaveable { mutableIntStateOf(0) }

    SurfaceCard {
        val choice = Choice.SOUND_MODE_CYCLE
        ChoiceItem(
            "Sound mode button", choice.options.map { it.first }, state.choices[choice], { actions.setChoice(choice, it) },
            icon = Icons.Rounded.SwapHoriz, subtitle = "Which modes the HearThrough/ANC/Off button action cycles through",
        )
    }

    PrimaryTabRow(selectedTabIndex = menu, containerColor = Color.Transparent) {
        MENU_TITLES.forEachIndexed { i, title ->
            Tab(
                selected = menu == i,
                onClick = { menu = i },
                text = { Text(title, maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelLarge) },
                unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    // Left and right side by side, as they sit in your ears.
    val assignments = state.buttons[menu].orEmpty()
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(Triple(0, Side.LEFT, "Left"), Triple(1, Side.RIGHT, "Right")).forEach { (earbud, side, title) ->
            Card(
                Modifier.weight(1f).fillMaxHeight(),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                // An earbud in the case still gets its buttons set; it's only drawn faded.
                val offline = state.isOffline(side)
                Column(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    EarbudIcon(
                        if (side == Side.LEFT) "L" else "R",
                        mirrored = side == Side.RIGHT,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = if (offline) 0.35f else 1f),
                    )
                    Text(
                        if (offline) "$title · offline" else title,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
                    )
                }
                assignments.filter { it.earbud == earbud }.sortedBy { it.gesture }.forEach { a ->
                    // Keep an action that isn't on the menu (e.g. the factory Spotify Tap) visible.
                    val options = Buttons.OPTIONS[a.menu].orEmpty().let { if (a.action in it) it else it + a.action }
                    CompactChoice(
                        Buttons.GESTURES[a.gesture] ?: "Gesture",
                        options.map { Buttons.actionName(it) },
                        options.indexOf(a.action).takeIf { it >= 0 },
                    ) { actions.setButton(a.copy(action = options[it])) }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
    if (menu == 3) {
        Hint("Experimental: Sound+ hides this menu. It seems to apply while a call you're making is still ringing.")
    }

    SectionTitle("Voice assistant")
    SurfaceCard {
        val options = Jabra.VOICE_ASSISTANTS
        val selected = options.indexOfFirst { it.second == state.voiceAssistant }.takeIf { it >= 0 }
        ChoiceItem(
            "Press and hold", options.map { it.first }, selected, { actions.setVoiceAssistant(options[it].second) },
            icon = Icons.Rounded.Assistant,
            subtitle = if (state.voiceAssistant == 0x11) {
                "Google Assistant needs to be set up on your phone, or press and hold may do nothing"
            } else {
                "Starts your phone's assistant, e.g. Gemini"
            },
        )
    }

    var confirmReset by remember { mutableStateOf(false) }
    TextButton(onClick = { confirmReset = true }, enabled = state.buttons.isNotEmpty()) {
        Icon(Icons.Rounded.SettingsBackupRestore, null, Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text("Restore factory button layout")
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Restore factory layout?") },
            text = {
                Text(
                    "All four menus go back to how Jabra set them. In Music, the left double press becomes Spotify Tap " +
                        "and the right double press becomes Next track.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    actions.resetButtons()
                }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}
