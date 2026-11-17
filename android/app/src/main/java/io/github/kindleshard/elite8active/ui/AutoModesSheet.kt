package io.github.kindleshard.elite8active.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsRun
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.AddLocationAlt
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.EventSeat
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kindleshard.elite8active.automodes.AutoModes
import io.github.kindleshard.elite8active.automodes.AutoRules
import io.github.kindleshard.elite8active.automodes.Detection
import io.github.kindleshard.elite8active.automodes.Motion
import io.github.kindleshard.elite8active.automodes.Place
import io.github.kindleshard.elite8active.findmy.FindMy
import io.github.kindleshard.elite8active.gnp.SoundMode
import kotlinx.coroutines.launch

/** "Leave it as it is", then the three modes, as offered for every rule. */
private val MODE_CHOICES: List<Pair<SoundMode?, String>> = listOf(null to "Leave as it is") + SoundMode.entries.map { it to it.label }

private fun Motion.icon(): ImageVector = when (this) {
    Motion.STILL -> Icons.Rounded.EventSeat
    Motion.WALKING -> Icons.AutoMirrored.Rounded.DirectionsWalk
    Motion.RUNNING -> Icons.AutoMirrored.Rounded.DirectionsRun
    Motion.VEHICLE -> Icons.Rounded.DirectionsCar
}

/** Settings → Automatic modes: a summary row that opens the rules. */
@Composable
fun AutoModesItem() {
    val rules by AutoModes.rules.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    val active = (if (Detection.CAN_DETECT_MOTION) rules.motion.count { it.value != null } else 0) + rules.places.count { it.mode != null }
    ListItem(
        headlineContent = { Text("Automatic modes") },
        supportingContent = {
            Text(
                when {
                    rules.enabled -> "On · $active ${if (active == 1) "rule" else "rules"}"
                    Detection.CAN_DETECT_MOTION -> "Switch modes as you move or arrive somewhere"
                    else -> "Switch modes when you arrive somewhere"
                },
            )
        },
        leadingContent = { Icon(Icons.Rounded.Route, null) },
        trailingContent = { Icon(Icons.Rounded.ChevronRight, null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable { open = true },
    )
    if (open) AutoModesSheet(rules, onDismiss = { open = false })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AutoModesSheet(rules: AutoRules, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Builds without activity recognition don't need (or ask for) the permission.
    var motionAccess by remember { mutableStateOf(!Detection.CAN_DETECT_MOTION || AutoModes.hasMotionAccess(context)) }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Place?>(null) }
    var locating by remember { mutableStateOf(false) }
    val askMotion = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        motionAccess = granted
        // Registering again picks up the new permission.
        AutoModes.update(context, rules.copy(enabled = true))
    }
    val placesAllowed = FindMy.hasLocationAccess(context)
    fun update(new: AutoRules) = AutoModes.update(context, new)

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            Text("Automatic modes", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            SwitchItem(
                "Change modes by themselves", "While the earbuds are connected",
                rules.enabled,
                { on ->
                    if (on && !motionAccess) askMotion.launch(Manifest.permission.ACTIVITY_RECOGNITION) else update(rules.copy(enabled = on))
                },
            )
            if (rules.enabled && !motionAccess) {
                Hint("Allow physical activity access for the rules below, or use places only.")
            }

            if (Detection.CAN_DETECT_MOTION) {
                SectionTitle("When you're")
                Motion.entries.forEach { m ->
                    ChoiceItem(
                        m.label, MODE_CHOICES.map { it.second }, MODE_CHOICES.indexOfFirst { it.first == rules.motion[m] },
                        { i -> update(rules.copy(motion = rules.motion + (m to MODE_CHOICES[i].first))) },
                        icon = m.icon(),
                    )
                }
            }

            SectionTitle("Places")
            rules.places.forEach { place ->
                ListItem(
                    headlineContent = { Text(place.name) },
                    supportingContent = { Text(place.mode?.label ?: "Leave as it is", color = MaterialTheme.colorScheme.primary) },
                    leadingContent = { Icon(Icons.Rounded.Place, null) },
                    trailingContent = {
                        IconButton(onClick = { update(rules.copy(places = rules.places - place)) }) { Icon(Icons.Rounded.Delete, "Delete ${place.name}") }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable { editing = place },
                )
            }
            TextButton(
                onClick = { adding = true },
                enabled = placesAllowed && !locating,
                modifier = Modifier.padding(horizontal = 12.dp),
            ) {
                Icon(Icons.Rounded.AddLocationAlt, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (locating) "Finding where you are…" else "Add where you are now")
            }
            Hint(
                if (placesAllowed) {
                    "A place covers about ${AutoModes.PLACE_RADIUS_M.toInt()} m around where you add it, and wins over what you're doing."
                } else {
                    "Places need location access \"Allow all the time\", the same as Find My on Home."
                },
            )
            Spacer(Modifier.height(8.dp))
            Hint("A mode you pick yourself stays until the next change, like starting to walk or arriving somewhere.")
        }
    }

    if (adding) {
        AddPlaceDialog(onDismiss = { adding = false }) { name ->
            adding = false
            locating = true
            scope.launch {
                FindMy.currentLocation(context)?.let { at ->
                    update(rules.copy(places = rules.places + AutoModes.newPlace(name, at.latitude, at.longitude, SoundMode.ANC)))
                }
                locating = false
            }
        }
    }
    editing?.let { place ->
        OptionSheet(place.name, MODE_CHOICES.map { it.second }, MODE_CHOICES.indexOfFirst { it.first == place.mode }, onDismiss = { editing = null }) { i ->
            update(rules.copy(places = rules.places.map { if (it.id == place.id) it.copy(mode = MODE_CHOICES[i].first) else it }))
            editing = null
        }
    }
}

@Composable
private fun AddPlaceDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add this place") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(24) },
                singleLine = true,
                placeholder = { Text("e.g. Gym, Office") },
                supportingText = { Text("Uses where your phone is now. It starts on ANC; tap it to change.") },
            )
        },
        confirmButton = { TextButton(onClick = { onAdd(name.trim()) }, enabled = name.isNotBlank()) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
