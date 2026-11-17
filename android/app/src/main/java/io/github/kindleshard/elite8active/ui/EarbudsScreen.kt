package io.github.kindleshard.elite8active.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.kindleshard.elite8active.ConnectionStatus
import io.github.kindleshard.elite8active.EarbudsActions
import io.github.kindleshard.elite8active.EarbudsState
import io.github.kindleshard.elite8active.EarbudsViewModel

private enum class AppTab(val label: String, val title: String, val icon: ImageVector) {
    HOME("Home", "Elite 8 Active", Icons.Rounded.Home),
    SOUND("Sound", "Sound", Icons.Rounded.GraphicEq),
    CONTROLS("Controls", "Controls", Icons.Rounded.TouchApp),
    SETTINGS("Settings", "Settings", Icons.Rounded.Settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EarbudsScreen(
    state: EarbudsState,
    permitted: Boolean,
    onRequestPermission: () -> Unit,
    actions: EarbudsActions,
    inCall: Boolean = false,
    openCall: Boolean = false,
    onOpenCallHandled: () -> Unit = {},
) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            actions.dismissError()
        }
    }

    // Once there's something to show, the tabs stay up through reconnects: the device card and
    // the title bar show the connection instead.
    if (!permitted || !state.hasData) {
        Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
            ConnectScreen(state, permitted, onRequestPermission, actions, Modifier.padding(padding))
        }
        return
    }

    var tab by rememberSaveable { mutableIntStateOf(0) }
    // The "On a call" notification: the call settings are at the top of Home.
    LaunchedEffect(openCall) {
        if (openCall) {
            tab = AppTab.HOME.ordinal
            onOpenCallHandled()
        }
    }
    val current = AppTab.entries[tab]
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AppLogo(32.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(current.title)
                        }
                    },
                    actions = {
                        if (current != AppTab.HOME) ConnectionPill(state, onRetry = actions::refresh)
                        IconButton(onClick = actions::refresh, enabled = !state.refreshing) {
                            Icon(Icons.Rounded.Refresh, "Refresh")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
                // A slim bar while talking to the earbuds; the controls stay usable.
                Box(Modifier.fillMaxWidth().height(2.dp)) {
                    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                AppTab.entries.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Icon(t.icon, null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = actions::refresh,
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            Crossfade(current, label = "tab") { t ->
                when (t) {
                    AppTab.HOME -> HomeTab(state, actions, inCall)
                    AppTab.SOUND -> SoundTab(state, actions)
                    AppTab.CONTROLS -> ControlsTab(state, actions)
                    AppTab.SETTINGS -> SettingsTab(state, actions)
                }
            }
        }
    }
}

/** Full-screen state shown until the first connection, and when it can't be made. */
@Composable
private fun ConnectScreen(
    state: EarbudsState,
    permitted: Boolean,
    onRequestPermission: () -> Unit,
    actions: EarbudsActions,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val failed = permitted && state.status == ConnectionStatus.Failed
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        AppLogo(132.dp)
        Spacer(Modifier.height(28.dp))
        val title = when {
            !permitted -> "Allow access to your earbuds"
            failed -> "Couldn't connect"
            else -> "Connecting…"
        }
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        val body = when {
            !permitted -> "The app needs the Nearby devices permission to talk to your Jabra earbuds over Bluetooth."
            failed -> "Take the earbuds out of the case and make sure they're connected to this phone." +
                if (state.soundPlusInstalled) {
                    "\n\nOnly one app can control the earbuds at a time. If Jabra Sound+ is running, open its app info and tap Force stop, then come back."
                } else {
                    ""
                }
            else -> "Looking for your Jabra Elite 8 Active."
        }
        Text(
            body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        when {
            !permitted -> Button(onClick = onRequestPermission) { Text("Allow") }
            failed -> {
                Button(onClick = actions::connect, modifier = Modifier.fillMaxWidth()) { Text("Try again") }
                if (state.soundPlusInstalled) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { openSoundPlusInfo(context) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Open Sound+ app info")
                    }
                }
                state.connectFailure?.let {
                    Spacer(Modifier.height(16.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, textAlign = TextAlign.Center)
                }
                // Lost earbuds are exactly when there's no connection, so Find My lives here too.
                Spacer(Modifier.height(28.dp))
                FindMyCard()
            }
            else -> CircularProgressIndicator()
        }
    }
}

/** Sound+'s app info page, where it can be force-stopped to free the control channel. */
fun openSoundPlusInfo(context: Context) {
    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${EarbudsViewModel.SOUND_PLUS}")))
}
