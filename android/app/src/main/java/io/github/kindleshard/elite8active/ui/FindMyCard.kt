package io.github.kindleshard.elite8active.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kindleshard.elite8active.findmy.EarbudsAudio
import io.github.kindleshard.elite8active.findmy.FindMy
import io.github.kindleshard.elite8active.findmy.LocatorSound
import io.github.kindleshard.elite8active.findmy.Sighting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/** Find My earbuds: switch, last known places, and a locator sound. Works without a connection. */
@Composable
fun FindMyCard() {
    val context = LocalContext.current
    val state by FindMy.state.collectAsStateWithLifecycle()
    var hasAccess by remember { mutableStateOf(FindMy.hasLocationAccess(context)) }
    LifecycleResumeEffect(Unit) {
        hasAccess = FindMy.hasLocationAccess(context)
        onPauseOrDispose {}
    }
    var explain by remember { mutableStateOf(false) }
    var denied by remember { mutableStateOf(false) }

    val background = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        hasAccess = FindMy.hasLocationAccess(context)
        denied = !ok
        if (ok) FindMy.setEnabled(true)
    }
    val foreground = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it }) background.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) else denied = true
    }

    SurfaceCard {
        SwitchItem(
            "Find my earbuds", "Saves where your earbuds disconnect. Stays on this phone.",
            state.enabled,
            { on -> if (!on) FindMy.setEnabled(false) else if (hasAccess) FindMy.setEnabled(true) else explain = true },
            icon = Icons.Rounded.Radar,
        )
        val places = if (state.enabled) state.places else emptyList()
        if (!state.enabled) {
            if (denied) LocationAccessWarning("Find My needs location access set to \"Allow all the time\".")
        } else {
            if (!hasAccess) LocationAccessWarning("Location access is off, so new places can't be saved.")
            if (places.isEmpty()) {
                Hint("Nothing saved yet. The place is saved the next time the earbuds disconnect.")
            } else {
                places.forEachIndexed { i, s -> PlaceRow(s, if (i == 0) "Last seen" else "Also disconnected here") }
            }
        }
        // The locator sound needs no location access, so it's there either way.
        LocatorSoundRow(canDelete = places.isNotEmpty())
    }

    if (explain) {
        AlertDialog(
            onDismissRequest = { explain = false },
            title = { Text("Find my earbuds") },
            text = {
                Text(
                    "When your earbuds disconnect, the app saves where your phone is, so you can find them if you lose them. " +
                        "Only the latest place is kept, and it never leaves this phone.\n\n" +
                        "The earbuds usually disconnect while the app is closed, so on the next screens allow location and then choose \"Allow all the time\".",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    explain = false
                    if (FindMy.hasForegroundLocation(context)) {
                        background.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    } else {
                        foreground.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    }
                }) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { explain = false }) { Text("Not now") } },
        )
    }
}

@Composable
private fun LocationAccessWarning(text: String) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        TextButton(onClick = {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
        }) { Text("Settings") }
    }
}

@Composable
private fun PlaceRow(s: Sighting, title: String) {
    val context = LocalContext.current
    var near by remember(s) { mutableStateOf<String?>(null) }
    LaunchedEffect(s) { near = withContext(Dispatchers.IO) { FindMy.placeName(context, s) } }
    val ago = DateUtils.getRelativeTimeSpanString(s.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
    val at = DateUtils.formatDateTime(context, s.time, DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            Column {
                Text(
                    near?.let { "Close to $it" } ?: String.format(Locale.US, "%.5f, %.5f", s.latitude, s.longitude),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text("$ago · $at · within ${s.accuracy.toInt().coerceAtLeast(1)} m", style = MaterialTheme.typography.bodySmall)
            }
        },
        leadingContent = { Icon(Icons.Rounded.Place, null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = { IconButton(onClick = { openMap(context, s) }) { Icon(Icons.Rounded.Map, "Show on map") } },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable { openMap(context, s) },
    )
}

@Composable
private fun LocatorSoundRow(canDelete: Boolean) {
    val context = LocalContext.current
    val locator = remember { LocatorSound(context) }
    var playing by remember { mutableStateOf(false) }
    var warn by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    DisposableEffect(locator) {
        locator.onStopped = { playing = false }
        onDispose { locator.stop() }
    }
    val audioConnected = rememberEarbudsAudioConnected()

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (playing) {
            Button(onClick = locator::stop, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                Icon(Icons.Rounded.Stop, null, Modifier.size(18.dp))
                Text("  Stop sound")
            }
        } else {
            Button(onClick = { warn = true }, enabled = audioConnected) {
                Icon(Icons.Rounded.NotificationsActive, null, Modifier.size(18.dp))
                Text("  Play sound")
            }
        }
        if (canDelete) {
            OutlinedButton(onClick = { confirmDelete = true }) {
                Icon(Icons.Rounded.Delete, null, Modifier.size(18.dp))
                Text("  Forget")
            }
        }
    }
    if (!audioConnected && !playing) Hint("Play sound works while the earbuds are connected to this phone.")

    if (warn) {
        AlertDialog(
            onDismissRequest = { warn = false },
            title = { Text("Play a loud sound?") },
            text = { Text("Take the earbuds out of your ears first. They'll beep at full volume for up to a minute.") },
            confirmButton = {
                TextButton(onClick = {
                    warn = false
                    playing = locator.start()
                }) { Text("Play") }
            },
            dismissButton = { TextButton(onClick = { warn = false }) { Text("Cancel") } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Forget saved places?") },
            text = { Text("The app won't be able to show where the earbuds were until they disconnect again.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    FindMy.clear()
                }) { Text("Forget") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

/** Whether the earbuds are connected as an audio output; updates as they connect and disconnect. */
@Composable
fun rememberEarbudsAudioConnected(includeCalls: Boolean = false): Boolean {
    val context = LocalContext.current
    var connected by remember { mutableStateOf(EarbudsAudio.output(context, includeCalls) != null) }
    DisposableEffect(Unit) {
        val audio = context.getSystemService(AudioManager::class.java)
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
                connected = EarbudsAudio.output(context, includeCalls) != null
            }

            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
                connected = EarbudsAudio.output(context, includeCalls) != null
            }
        }
        audio.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        onDispose { audio.unregisterAudioDeviceCallback(callback) }
    }
    return connected
}

private fun openMap(context: Context, s: Sighting) {
    val point = "${s.latitude},${s.longitude}"
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$point?q=$point(${Uri.encode("Earbuds")})")))
    } catch (_: ActivityNotFoundException) {
        openLink(context, "https://www.google.com/maps/search/?api=1&query=$point")
    }
}
