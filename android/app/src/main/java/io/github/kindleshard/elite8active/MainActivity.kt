package io.github.kindleshard.elite8active

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kindleshard.elite8active.automodes.AutoModes
import io.github.kindleshard.elite8active.calls.CallAccess
import io.github.kindleshard.elite8active.findmy.FindMy
import io.github.kindleshard.elite8active.notify.Notifications
import io.github.kindleshard.elite8active.store.EqLibrary
import io.github.kindleshard.elite8active.ui.EarbudsScreen
import io.github.kindleshard.elite8active.ui.theme.EliteTheme

class MainActivity : ComponentActivity() {
    private val viewModel: EarbudsViewModel by viewModels()

    /** Set when opened from the "On a call" notification. */
    private var openCall by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FindMy.init(this)
        EqLibrary.init(this)
        AutoModes.init(this)
        // Android can drop activity and place registrations (e.g. when Play services updates).
        AutoModes.register(this)
        openCall = intent.wantsCallSettings()
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            EliteTheme {
                // Calls in progress, from the audio mode (no permission needed).
                var inCall by remember { mutableStateOf(false) }
                DisposableEffect(Unit) {
                    val audio = getSystemService(AudioManager::class.java)
                    inCall = CallAccess.isCallMode(audio.mode)
                    val listener = AudioManager.OnModeChangedListener { inCall = CallAccess.isCallMode(it) }
                    audio.addOnModeChangedListener(mainExecutor, listener)
                    onDispose { audio.removeOnModeChangedListener(listener) }
                }
                var permitted by remember {
                    mutableStateOf(
                        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                            PackageManager.PERMISSION_GRANTED,
                    )
                }
                val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                    permitted = it
                }
                LaunchedEffect(permitted) {
                    if (!permitted) askPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
                }
                // Hold the earbuds' control channel only while the screen is visible, so Sound+
                // and other apps can use it when this app is in the background.
                if (permitted) {
                    LifecycleStartEffect(Unit) {
                        viewModel.onForeground()
                        onStopOrDispose { viewModel.onBackground() }
                    }
                }
                val state by viewModel.state.collectAsStateWithLifecycle()
                EarbudsScreen(
                    state = state,
                    permitted = permitted,
                    onRequestPermission = { askPermission.launch(Manifest.permission.BLUETOOTH_CONNECT) },
                    actions = viewModel,
                    inCall = inCall,
                    openCall = openCall,
                    onOpenCallHandled = { openCall = false },
                )
            }
        }
    }

    // No battery pop-up over the app itself.
    override fun onStart() {
        super.onStart()
        Notifications.appVisible = true
    }

    override fun onStop() {
        super.onStop()
        Notifications.appVisible = false
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.wantsCallSettings()) openCall = true
    }

    private fun Intent?.wantsCallSettings() = this?.getStringExtra(CallAccess.EXTRA_OPEN) == CallAccess.OPEN_CALL
}
