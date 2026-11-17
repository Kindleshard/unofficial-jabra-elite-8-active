package io.github.kindleshard.elite8active.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Hearing
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.kindleshard.elite8active.EarbudsActions
import io.github.kindleshard.elite8active.gnp.EqPreset
import io.github.kindleshard.elite8active.gnp.Jabra
import io.github.kindleshard.elite8active.hearing.HearingProfile
import io.github.kindleshard.elite8active.hearing.ToneTester

/** Settings → Experimental: things Sound+ doesn't offer for these earbuds. */
@Composable
fun ExperimentalItems(actions: EarbudsActions) {
    var testing by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text("Hearing test") },
        supportingContent = { Text("A two-minute listening test that builds an EQ for your ears") },
        leadingContent = { Icon(Icons.Rounded.Hearing, null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable { testing = true },
    )
    if (testing) HearingTestDialog(onApply = actions::setEqGains, onDismiss = { testing = false })
}

// ---------------------------------------------------------------- Hearing test

private sealed interface TestStep {
    data object Intro : TestStep
    data class Tone(val index: Int) : TestStep
    data class Result(val gains: List<Int>) : TestStep
}

@Composable
private fun HearingTestDialog(onApply: (List<Int>) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val tester = remember { ToneTester(context) }
    var step by remember { mutableStateOf<TestStep>(TestStep.Intro) }
    var failed by remember { mutableStateOf(false) }
    val trials = remember { HearingProfile.FREQUENCIES.flatMap { f -> listOf(f to true, f to false) } }
    val left = remember { DoubleArray(HearingProfile.FREQUENCIES.size) }
    val right = remember { DoubleArray(HearingProfile.FREQUENCIES.size) }
    var running by remember { mutableIntStateOf(-1) }

    DisposableEffect(Unit) { onDispose { tester.end() } }
    LaunchedEffect(running) {
        if (running < 0) return@LaunchedEffect
        for (i in trials.indices) {
            step = TestStep.Tone(i)
            val (frequency, leftEar) = trials[i]
            val level = tester.threshold(frequency, leftEar)
            val band = HearingProfile.FREQUENCIES.indexOf(frequency)
            if (leftEar) left[band] = level else right[band] = level
        }
        tester.end()
        step = TestStep.Result(HearingProfile.eqGains(left.toList(), right.toList()))
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Hearing test", style = MaterialTheme.typography.headlineSmall)
                when (val s = step) {
                    TestStep.Intro -> {
                        Text(
                            "Find a quiet place and wear both earbuds. You'll hear soft beeps in one ear at a time, getting louder. " +
                                "Tap the big button as soon as you hear one. There are 10 beeps, about two minutes in all.",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            "This compares how well you hear each EQ band and builds a curve to even it out. It isn't a medical " +
                                "hearing test. Your media volume is set to 60 % during the test and put back afterwards.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (failed) Text("Connect the earbuds first.", color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.weight(1f))
                        Button(onClick = { if (tester.begin()) running = 1 else failed = true }, modifier = Modifier.fillMaxWidth()) {
                            Text("Start")
                        }
                        TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
                    }
                    is TestStep.Tone -> {
                        val (frequency, leftEar) = trials[s.index]
                        LinearProgressIndicator(progress = { s.index / trials.size.toFloat() }, modifier = Modifier.fillMaxWidth())
                        Text(
                            "${if (leftEar) "Left" else "Right"} ear · ${Jabra.EQ_BAND_LABELS[HearingProfile.FREQUENCIES.indexOf(frequency)]}" +
                                " · ${s.index + 1} of ${trials.size}",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.weight(1f))
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Surface(
                                onClick = { tester.tap() },
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(200.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text("I hear it", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onPrimary)
                                }
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        Text(
                            "Tap as soon as you hear a beep, even a faint one.",
                            Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Stop") }
                    }
                    is TestStep.Result -> {
                        val flat = s.gains.all { it == 0 }
                        Text(
                            if (flat) "You heard every band about as well as typical hearing, so no correction is needed."
                            else "Here's the curve that evens out the bands you heard less well.",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        SurfaceCard {
                            Column(Modifier.padding(16.dp)) {
                                EqCurve(s.gains, height = 120.dp)
                                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Jabra.EQ_BAND_LABELS.forEachIndexed { i, label ->
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Text("%+.1f".format(s.gains[i] / 100f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                }
                            }
                        }
                        Text("Left and right ear", style = MaterialTheme.typography.titleSmall)
                        EarBars(left.toList(), right.toList())
                        Spacer(Modifier.weight(1f))
                        Button(onClick = {
                            onApply(if (flat) EqPreset.NEUTRAL.gains else s.gains)
                            onDismiss()
                        }, modifier = Modifier.fillMaxWidth()) { Text(if (flat) "Use Neutral" else "Apply to equalizer") }
                        TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Close") }
                    }
                }
            }
        }
    }
}

/** How loud each tone had to be, per ear: taller = needed louder. */
@Composable
private fun EarBars(left: List<Double>, right: List<Double>) {
    val range = ToneTester.MAX_DB - ToneTester.START_DB
    Row(Modifier.fillMaxWidth().height(90.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
        Jabra.EQ_BAND_LABELS.indices.forEach { i ->
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxHeight()) {
                listOf(left[i] to MaterialTheme.colorScheme.primary, right[i] to MaterialTheme.colorScheme.tertiary).forEach { (level, color) ->
                    val fraction = ((level - ToneTester.START_DB) / range).coerceIn(0.05, 1.0).toFloat()
                    Box(Modifier.width(12.dp).fillMaxHeight(fraction).background(color, RoundedCornerShape(4.dp)))
                }
            }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Legend("Left", MaterialTheme.colorScheme.primary)
        Legend("Right", MaterialTheme.colorScheme.tertiary)
    }
}

@Composable
private fun Legend(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}
