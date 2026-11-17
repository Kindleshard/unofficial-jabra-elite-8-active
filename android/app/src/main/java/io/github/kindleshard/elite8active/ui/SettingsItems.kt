package io.github.kindleshard.elite8active.ui

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.HearingDisabled
import androidx.compose.material.icons.rounded.HowToReg
import androidx.compose.material.icons.rounded.PhoneInTalk
import androidx.compose.material.icons.rounded.QuestionAnswer
import androidx.compose.material.icons.rounded.SupportAgent
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.kindleshard.elite8active.R
import io.github.kindleshard.elite8active.calls.CallAccess
import io.github.kindleshard.elite8active.tile.AncTileService

/** Settings switch for the "On a call" notification. */
@Composable
fun CallQuickAccessItem() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(CallAccess.isEnabled(context) && CallAccess.hasPermissions(context)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        enabled = CallAccess.hasPermissions(context)
        CallAccess.setEnabled(context, enabled)
    }
    SwitchItem(
        "Quick access during calls", "A notification that opens these settings while you're on a call",
        enabled,
        { on ->
            if (on && !CallAccess.hasPermissions(context)) {
                ask.launch(CallAccess.PERMISSIONS)
            } else {
                CallAccess.setEnabled(context, on)
                enabled = on
            }
        },
        icon = Icons.Rounded.PhoneInTalk,
    )
}

/** Asks Android to add the ANC tile to the Quick Settings panel (the system shows a confirm dialog). */
@Composable
fun QuickTileItem() {
    val context = LocalContext.current
    var result by remember { mutableStateOf<String?>(null) }
    ListItem(
        headlineContent = { Text("Quick Settings tile") },
        supportingContent = { Text(result ?: "Turn ANC on and off and see battery from the notification shade") },
        leadingContent = { Icon(Icons.Rounded.HearingDisabled, null) },
        trailingContent = {
            TextButton(onClick = {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    result = "Edit your Quick Settings and drag in \"ANC\"."
                    return@TextButton
                }
                context.getSystemService(StatusBarManager::class.java)?.requestAddTileService(
                    ComponentName(context, AncTileService::class.java),
                    "ANC",
                    Icon.createWithResource(context, R.drawable.ic_stat_earbuds),
                    context.mainExecutor,
                ) { code ->
                    result = when (code) {
                        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "Added. Swipe down twice to find it."
                        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "Already in your Quick Settings."
                        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> null
                        else -> "Couldn't add it here. Edit your Quick Settings and drag in \"ANC\"."
                    }
                }
            }) { Text("Add") }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

// ---------------------------------------------------------------- Help

private object Links {
    // From Jabra Sound+'s own resources for the Elite 8 Active (codename "pelican").
    const val SUPPORT = "https://www.jabra.com/supportpages/jabra-elite-8-active"
    const val FAQ = "https://www.jabra.com/supportpages/jabra-elite-8-active#/FAQ"
    const val CONTACT = "https://www.jabra.com/support"
    const val REGISTER = "https://www.jabra.com/product-registration"
}

@Composable
fun HelpLinks() {
    LinkItem("User manual", "Guides and manuals on jabra.com", Icons.AutoMirrored.Rounded.MenuBook, Links.SUPPORT)
    LinkItem("FAQ", "Answers to common questions", Icons.Rounded.QuestionAnswer, Links.FAQ)
    LinkItem("Contact Jabra Support", "Jabra's support site", Icons.Rounded.SupportAgent, Links.CONTACT)
    LinkItem("Register your earbuds", "With Jabra, for warranty and updates", Icons.Rounded.HowToReg, Links.REGISTER)
}

@Composable
private fun LinkItem(title: String, subtitle: String, icon: ImageVector, url: String) {
    val context = LocalContext.current
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = { Icon(icon, null) },
        trailingContent = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, "Opens in your browser", Modifier.size(18.dp)) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable { openLink(context, url) },
    )
}

fun openLink(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}
