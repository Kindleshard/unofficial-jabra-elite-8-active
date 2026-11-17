package io.github.kindleshard.elite8active.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.github.kindleshard.elite8active.gnp.Jabra
import kotlin.math.roundToInt

/** Scrollable page body shared by every tab. */
@Composable
fun TabPage(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(4.dp))
        content()
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 8.dp, top = 8.dp),
    )
}

@Composable
fun SurfaceCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) { Column(Modifier.padding(vertical = 6.dp), content = content) }
}

@Composable
fun SwitchItem(
    title: String,
    subtitle: String?,
    checked: Boolean?,
    onChange: (Boolean) -> Unit,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val usable = enabled && checked != null
    val scheme = MaterialTheme.colorScheme
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        leadingContent = icon?.let { { Icon(it, null) } },
        trailingContent = {
            Switch(
                checked = checked == true,
                onCheckedChange = onChange,
                enabled = usable,
                // Keep the on/off state readable while the row is dimmed.
                colors = SwitchDefaults.colors(
                    disabledCheckedTrackColor = scheme.primary.copy(alpha = 0.5f),
                    disabledCheckedThumbColor = scheme.onPrimary,
                    disabledUncheckedTrackColor = scheme.surfaceContainerHighest,
                    disabledUncheckedThumbColor = scheme.outline,
                    disabledUncheckedBorderColor = scheme.outline,
                ),
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier
            .alpha(if (usable || checked == null) 1f else 0.5f)
            .clickable(enabled = usable) { onChange(checked != true) },
    )
}

/** A row showing the current value; tapping opens a sheet with all options. */
@Composable
fun ChoiceItem(
    title: String,
    options: List<String>,
    selected: Int?,
    onSelect: (Int) -> Unit,
    icon: ImageVector? = null,
    subtitle: String? = null,
) {
    var open by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            Column {
                Text(
                    selected?.let { options.getOrNull(it) } ?: "–",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                )
                subtitle?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        leadingContent = icon?.let { { Icon(it, null) } },
        trailingContent = { Icon(Icons.Rounded.ChevronRight, null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(enabled = selected != null) { open = true },
    )
    if (open) {
        OptionSheet(title, options, selected, onDismiss = { open = false }) {
            open = false
            if (it != selected) onSelect(it)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OptionSheet(title: String, options: List<String>, selected: Int?, onDismiss: () -> Unit, onSelect: (Int) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        options.forEachIndexed { i, label ->
            val isSelected = i == selected
            ListItem(
                headlineContent = {
                    Text(label, color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                },
                trailingContent = if (isSelected) {
                    { Icon(Icons.Rounded.Check, "Selected", tint = MaterialTheme.colorScheme.primary) }
                } else {
                    null
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable { onSelect(i) }.padding(horizontal = 8.dp),
            )
        }
        Spacer(Modifier.height(32.dp))
    }
}

/**
 * A compact choice for narrow columns, drawn as a tappable tonal button: the title, the current
 * value, and a chevron. Tapping opens a sheet with all options.
 */
@Composable
fun CompactChoice(title: String, options: List<String>, selected: Int?, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Surface(
        onClick = { open = true },
        enabled = selected != null,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Row(Modifier.padding(start = 12.dp, end = 6.dp, top = 9.dp, bottom = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    selected?.let { options.getOrNull(it) } ?: "–",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Icon(Icons.Rounded.ExpandMore, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (open) {
        OptionSheet(title, options, selected, onDismiss = { open = false }) {
            open = false
            if (it != selected) onSelect(it)
        }
    }
}

/** A slider with a few fixed steps (0 until [steps]); sent when released, like the EQ sliders. */
@Composable
fun StepSlider(
    title: String,
    steps: Int,
    value: Int?,
    onChange: (Int) -> Unit,
    low: String,
    high: String,
    icon: ImageVector? = null,
) {
    var local by remember(value) { mutableFloatStateOf((value ?: 0).toFloat()) }
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            icon?.let {
                Icon(it, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.size(16.dp))
            }
            Text(title, style = MaterialTheme.typography.bodyLarge)
        }
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onChange(local.roundToInt()) },
            valueRange = 0f..(steps - 1).toFloat(),
            steps = steps - 2,
            enabled = value != null,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(low, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(high, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun RenameDialog(current: String, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var text by remember { mutableStateOf(current) }
    val valid = text.isNotBlank() && text.all { it.code in 0x20..0x7E }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename earbuds") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(Jabra.NAME_MAX_LENGTH) },
                singleLine = true,
                isError = !valid,
                supportingText = { Text("Shown when pairing. No emoji. ${text.length}/${Jabra.NAME_MAX_LENGTH}") },
            )
        },
        confirmButton = { TextButton(onClick = { onRename(text) }, enabled = valid) { Text("Rename") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun Hint(text: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
