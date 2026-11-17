package io.github.kindleshard.elite8active.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.kindleshard.elite8active.battery.BatteryHealth
import io.github.kindleshard.elite8active.battery.BatteryLog
import io.github.kindleshard.elite8active.battery.HealthReport
import io.github.kindleshard.elite8active.ui.theme.Good
import io.github.kindleshard.elite8active.ui.theme.Low
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Under the battery gauges: the current verdict; tap for the details. */
@Composable
fun BatteryHealthButton(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<HealthReport?>(null) }
    var tracking by remember { mutableStateOf(BatteryLog.isTracking(context)) }
    LifecycleResumeEffect(open) {
        tracking = BatteryLog.isTracking(context)
        onPauseOrDispose {}
    }
    LaunchedEffect(open, tracking) {
        report = withContext(Dispatchers.IO) { BatteryHealth.analyze(BatteryLog.load(context)) }
    }
    TextButton(onClick = { open = true }, modifier = modifier) {
        Icon(Icons.Rounded.BatteryChargingFull, null, Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            "Battery health: " + when {
                !tracking && report?.left == null && report?.right == null -> "check"
                else -> report?.summary?.lowercase() ?: "…"
            },
        )
    }
    if (open) BatteryHealthSheet(report, tracking, onTracking = {
        BatteryLog.setTracking(context, it)
        tracking = it
    }, onDismiss = { open = false })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BatteryHealthSheet(report: HealthReport?, tracking: Boolean, onTracking: (Boolean) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(bottom = 32.dp)) {
            Text("Battery health", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            SwitchItem(
                "Track battery health", "Checks the battery every 15 minutes while the earbuds are connected",
                tracking, onTracking, icon = Icons.Rounded.Insights,
            )
            listOf("Left earbud" to report?.left, "Right earbud" to report?.right).forEach { (name, side) ->
                ListItem(
                    headlineContent = { Text(name) },
                    supportingContent = {
                        Text(
                            side?.let {
                                "About %.1f h per charge at your mix of ANC · %d %% of Jabra's rating".format(it.runtimeHours, it.percentOfRated)
                            } ?: "Not enough data yet. Needs about an hour of listening.",
                        )
                    },
                    trailingContent = side?.let { { Text(it.verdict, color = verdictColor(it.verdict), style = MaterialTheme.typography.labelLarge) } },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
            if (report?.uneven == true) {
                Hint("One earbud drains clearly faster than the other. That usually means its battery has aged more.")
            }
            Hint(
                "Measured from how fast each earbud drains while you use it, compared with Jabra's rating of up to 8 h with ANC on " +
                    "and 14 h with ANC off. Readings move in 5 % steps, so the estimate gets better with more listening." +
                    (report?.let { r ->
                        val hours = maxOf(r.left?.hoursObserved ?: 0.0, r.right?.hoursObserved ?: 0.0)
                        if (hours > 0) " Based on %.1f h of use.".format(hours) else ""
                    } ?: ""),
            )
        }
    }
}

@Composable
private fun verdictColor(verdict: String) = when (verdict) {
    "Good" -> Good
    "Some wear" -> Low
    else -> MaterialTheme.colorScheme.error
}
