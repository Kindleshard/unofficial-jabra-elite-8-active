package io.github.kindleshard.elite8active.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.kindleshard.elite8active.store.EqLibrary
import io.github.kindleshard.elite8active.store.SavedEq

/** Your saved EQ curves as tiles, three per row: tap to use one, long-press to delete it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SavedEqGrid(items: List<SavedEq>, selected: List<Int>?, enabled: Boolean, onSelect: (SavedEq) -> Unit) {
    val context = LocalContext.current
    var deleting by remember { mutableStateOf<SavedEq?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { item ->
                    val shape = RoundedCornerShape(18.dp)
                    val isSelected = item.gains == selected
                    Surface(
                        shape = shape,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(4f / 3f)
                            .clip(shape)
                            .combinedClickable(enabled = enabled, onLongClick = { deleting = item }) { onSelect(item) },
                    ) {
                        Column(Modifier.fillMaxSize().padding(start = 11.dp, end = 8.dp, top = 9.dp, bottom = 6.dp)) {
                            Text(item.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.weight(1f))
                            EqCurve(item.gains, height = 30.dp, showPoints = false)
                        }
                    }
                }
                // Keep the last row's tiles the same width as the others.
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
    deleting?.let { item ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete \"${item.name}\"?") },
            text = { Text("It's only removed from this list; the earbuds keep their current sound.") },
            confirmButton = {
                TextButton(onClick = {
                    EqLibrary.delete(context, item)
                    deleting = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
fun SaveEqDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save this sound") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(EqLibrary.NAME_MAX_LENGTH) },
                singleLine = true,
                placeholder = { Text("e.g. Podcasts") },
                supportingText = { Text("Shown under My presets. Saving a name again replaces it.") },
            )
        },
        confirmButton = { TextButton(onClick = { onSave(name) }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
