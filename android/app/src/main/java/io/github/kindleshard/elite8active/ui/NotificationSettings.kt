package io.github.kindleshard.elite8active.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.NotListedLocation
import androidx.compose.material.icons.rounded.Battery2Bar
import androidx.compose.material.icons.rounded.BluetoothConnected
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kindleshard.elite8active.battery.BatteryAlerts
import io.github.kindleshard.elite8active.findmy.FindMy
import io.github.kindleshard.elite8active.findmy.LeftBehind
import io.github.kindleshard.elite8active.firmware.FirmwareCheck
import io.github.kindleshard.elite8active.notify.Notifications

/**
 * Runs an action that needs the notification permission, asking for it first if needed (the
 * action is dropped if it's refused).
 */
@Composable
fun rememberNotificationGate(): (() -> Unit) -> Unit {
    val context = LocalContext.current
    var waiting by remember { mutableStateOf<(() -> Unit)?>(null) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) waiting?.invoke()
        waiting = null
    }
    return { action ->
        if (Notifications.allowed(context)) {
            action()
        } else {
            waiting = action
            ask.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

/** Settings → Notifications: the alerts that come from the earbuds while the app is closed. */
@Composable
fun NotificationItems() {
    val context = LocalContext.current
    val gate = rememberNotificationGate()
    var popup by remember { mutableStateOf(BatteryAlerts.isPopupOn(context)) }
    var low by remember { mutableStateOf(BatteryAlerts.isLowAlertOn(context)) }
    var leftBehind by remember { mutableStateOf(LeftBehind.isOn(context)) }
    var firmware by remember { mutableStateOf(FirmwareCheck.isOn(context)) }
    val findMy by FindMy.state.collectAsStateWithLifecycle()

    fun toggle(on: Boolean, set: (Boolean) -> Unit) = if (on) gate { set(true) } else set(false)

    SwitchItem(
        "Battery when connecting", "A short pop-up with each earbud's and the case's battery",
        popup, { on -> toggle(on) { BatteryAlerts.setPopup(context, it); popup = it } },
        icon = Icons.Rounded.BluetoothConnected,
    )
    SwitchItem(
        "Low battery", "When an earbud or the case drops to ${BatteryAlerts.LOW} %",
        low, { on -> toggle(on) { BatteryAlerts.setLowAlert(context, it); low = it } },
        icon = Icons.Rounded.Battery2Bar,
    )
    val canWatch = findMy.enabled && FindMy.hasLocationAccess(context)
    SwitchItem(
        "Left behind",
        if (canWatch) "When they disconnect and you move away soon after" else "Turn on Find My (on Home) first",
        leftBehind && canWatch, { on -> toggle(on) { LeftBehind.setOn(context, it); leftBehind = it } },
        icon = Icons.AutoMirrored.Rounded.NotListedLocation,
        enabled = canWatch,
    )
    SwitchItem(
        "Firmware updates", "Checks Jabra's release notes once a week",
        firmware, { on -> toggle(on) { FirmwareCheck.setOn(context, it); firmware = it } },
        icon = Icons.Rounded.SystemUpdate,
    )
}
