package io.github.kindleshard.elite8active.tile

import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.kindleshard.elite8active.R
import io.github.kindleshard.elite8active.battery.BatteryReports
import io.github.kindleshard.elite8active.bt.EarbudsLink
import io.github.kindleshard.elite8active.bt.readBattery
import io.github.kindleshard.elite8active.bt.readSoundMode
import io.github.kindleshard.elite8active.bt.writeSoundMode
import io.github.kindleshard.elite8active.gnp.Battery
import io.github.kindleshard.elite8active.gnp.SoundMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** What the tile last knew, so it can show something the moment the shade opens. */
object TileState {
    data class Snapshot(val mode: SoundMode?, val left: Int?, val right: Int?, val updated: Long, val lastOther: SoundMode)

    private fun prefs(context: Context) = context.getSharedPreferences("tile", Context.MODE_PRIVATE)

    fun load(context: Context): Snapshot {
        val p = prefs(context)
        return Snapshot(
            mode = SoundMode.of(p.getInt("mode", -1)),
            left = p.getInt("left", -1).takeIf { it >= 0 },
            right = p.getInt("right", -1).takeIf { it >= 0 },
            updated = p.getLong("updated", 0),
            lastOther = SoundMode.of(p.getInt("last_other", -1)) ?: SoundMode.OFF,
        )
    }

    /** Called with fresh values from the screen or the tile; redraws the tile if anything changed. */
    fun save(context: Context, mode: SoundMode? = null, battery: Battery? = null) {
        val before = load(context)
        prefs(context).edit().apply {
            mode?.let {
                putInt("mode", it.code)
                // Where tapping the tile again should go when it turns ANC off.
                if (it != SoundMode.ANC) putInt("last_other", it.code)
            }
            battery?.let {
                putInt("left", it.left ?: -1)
                putInt("right", it.right ?: -1)
            }
            putLong("updated", System.currentTimeMillis())
        }.apply()
        val after = load(context)
        if (after.mode != before.mode || after.left != before.left || after.right != before.right) {
            runCatching { TileService.requestListeningState(context, ComponentName(context, AncTileService::class.java)) }
        }
    }
}

/**
 * Quick Settings tile: tap to turn ANC on, tap again to go back to the previous mode; the
 * subtitle shows the earbuds' battery. Uses the app's connection when the app is open,
 * otherwise connects for a couple of seconds and lets go again.
 */
class AncTileService : TileService() {
    private var busy = false
    private var pending: SoundMode? = null
    private var problem: String? = null
    /** A tap that came in while the tile was still reading the earbuds; handled right after. */
    private var tapWaiting = false

    override fun onStartListening() {
        render()
        if (System.currentTimeMillis() - TileState.load(this).updated > REFRESH_MS) run(target = null)
    }

    override fun onClick() {
        if (busy) {
            tapWaiting = true
            return
        }
        val now = TileState.load(this)
        run(if (now.mode == SoundMode.ANC) now.lastOther else SoundMode.ANC)
    }

    /** Reads the sound mode and battery, after switching to [target] if it's set. */
    private fun run(target: SoundMode?) {
        if (busy) return
        busy = true
        pending = target
        problem = null
        render()
        val app = applicationContext
        EarbudsLink.scope.launch {
            val error = runCatching {
                withTimeout(25_000) {
                    EarbudsLink.use(app) { c ->
                        if (target != null) c.writeSoundMode(target)
                        BatteryReports.report(app, c.readBattery(), c.readSoundMode())
                    }
                }
            }.exceptionOrNull()
            withContext(Dispatchers.Main) {
                busy = false
                pending = null
                problem = error?.let { "Not reachable" }
                render()
                if (tapWaiting) {
                    tapWaiting = false
                    if (error == null) onClick()
                }
            }
        }
    }

    private fun render() {
        val tile = qsTile ?: return
        val s = TileState.load(this)
        val mode = pending ?: s.mode
        val on = mode == SoundMode.ANC
        tile.label = "ANC"
        tile.icon = Icon.createWithResource(this, R.drawable.ic_stat_earbuds)
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.stateDescription = if (on) "On" else "Off"
        tile.subtitle = when {
            busy && pending != null -> if (pending == SoundMode.ANC) "Turning on…" else "Turning off…"
            busy && s.mode == null -> "Connecting…"
            problem != null -> problem
            s.left != null || s.right != null -> batteryText(s.left, s.right)
            else -> "Tap to connect"
        }
        tile.updateTile()
    }

    companion object {
        /** How old the shown state may get before opening the shade checks the earbuds again. */
        private const val REFRESH_MS = 2 * 60_000L

        fun batteryText(left: Int?, right: Int?) =
            listOfNotNull(left?.let { "L $it%" }, right?.let { "R $it%" }).joinToString(" · ")
    }
}
