package io.github.kindleshard.elite8active.shortcuts

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import io.github.kindleshard.elite8active.bt.EarbudsLink
import io.github.kindleshard.elite8active.bt.writeSoundMode
import io.github.kindleshard.elite8active.gnp.SoundMode
import io.github.kindleshard.elite8active.tile.TileState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * The app icon's shortcuts (res/xml/shortcuts.xml): ANC, HearThrough and Off. They can also be
 * pinned to the home screen, or run from automation apps and One UI's Modes and Routines as an
 * app action. Nothing is shown but a short confirmation.
 */
class ModeShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SoundMode.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_MODE) }?.let { switchTo(applicationContext, it) }
        finish()
    }

    companion object {
        const val EXTRA_MODE = "mode"

        /** Uses the app's connection if it has one, otherwise connects for a moment. */
        fun switchTo(context: Context, mode: SoundMode) {
            EarbudsLink.scope.launch {
                val ok = runCatching {
                    withTimeout(20_000) { EarbudsLink.use(context) { it.writeSoundMode(mode) } }
                }.isSuccess
                if (ok) TileState.save(context, mode = mode)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, if (ok) "${mode.label} on" else "Couldn't reach the earbuds", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
