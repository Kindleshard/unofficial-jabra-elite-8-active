package io.github.kindleshard.elite8active.automodes

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.kindleshard.elite8active.bt.EarbudClient
import io.github.kindleshard.elite8active.bt.EarbudsLink
import io.github.kindleshard.elite8active.bt.readSoundMode
import io.github.kindleshard.elite8active.bt.writeSoundMode
import io.github.kindleshard.elite8active.findmy.EarbudsAudio
import io.github.kindleshard.elite8active.findmy.FindMy
import io.github.kindleshard.elite8active.gnp.SoundMode
import io.github.kindleshard.elite8active.notify.Notifications
import io.github.kindleshard.elite8active.tile.TileState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** What you're doing, as Android's activity recognition reports it (only in builds that have it, see [Detection]). */
enum class Motion(val label: String) {
    STILL("Sitting still"),
    WALKING("Walking"),
    RUNNING("Running"),
    VEHICLE("In a vehicle"),
}

/** A saved place: inside [AutoModes.PLACE_RADIUS_M] of it, the earbuds switch to [mode]. */
data class Place(val id: String, val name: String, val latitude: Double, val longitude: Double, val mode: SoundMode?)

/** The rules for switching modes by themselves. A null mode means "leave it as it is". */
data class AutoRules(
    val enabled: Boolean = false,
    val motion: Map<Motion, SoundMode?> = DEFAULT_MOTION,
    val places: List<Place> = emptyList(),
) {
    /** The mode to switch to and why; where you are wins over what you're doing. */
    fun pick(inside: Set<String>, motion: Motion?): Pair<SoundMode, String>? {
        places.firstOrNull { it.id in inside && it.mode != null }?.let { return it.mode!! to "At ${it.name}" }
        val mode = motion?.let { this.motion[it] } ?: return null
        return mode to motion.label
    }

    companion object {
        // Like Sony's adaptive sound control: hear the world on the move, block it in transport.
        val DEFAULT_MOTION = mapOf(
            Motion.STILL to null,
            Motion.WALKING to SoundMode.HEAR_THROUGH,
            Motion.RUNNING to SoundMode.HEAR_THROUGH,
            Motion.VEHICLE to SoundMode.ANC,
        )
    }
}

/**
 * Automatic sound modes: Android reports when you start walking, running, sitting still or
 * travelling, and when you arrive at or leave a saved place; if the earbuds are connected, the app
 * switches them to the mode set for it. A change you make by hand stays until the next one.
 *
 * How Android is asked is in [Detection], which differs per build: the "full" build uses Google
 * Play services, the "fdroid" build only Android itself (places, but not what you're doing).
 */
object AutoModes {
    const val PLACE_RADIUS_M = 150f
    internal const val ACTION_MOTION = "io.github.kindleshard.elite8active.automodes.MOTION"
    internal const val ACTION_PLACE = "io.github.kindleshard.elite8active.automodes.PLACE"
    /** Activity recognition can flicker between states; don't switch more often than this. */
    private const val MIN_GAP_MS = 30_000L

    private val _rules = MutableStateFlow(AutoRules())
    val rules: StateFlow<AutoRules> = _rules

    private fun prefs(context: Context): SharedPreferences = context.getSharedPreferences("auto_modes", Context.MODE_PRIVATE)

    fun init(context: Context) {
        _rules.value = load(context)
    }

    fun isOn(context: Context) = prefs(context).getBoolean("enabled", false)

    fun hasMotionAccess(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    fun load(context: Context): AutoRules {
        val p = prefs(context)
        val motion = Motion.entries.associateWith { m ->
            if (p.contains("motion_${m.name}")) SoundMode.of(p.getInt("motion_${m.name}", -1)) else AutoRules.DEFAULT_MOTION[m]
        }
        val places = runCatching {
            val a = JSONArray(p.getString("places", "[]"))
            (0 until a.length()).map { i ->
                a.getJSONObject(i).let {
                    Place(it.getString("id"), it.getString("name"), it.getDouble("lat"), it.getDouble("lng"), SoundMode.of(it.optInt("mode", -1)))
                }
            }
        }.getOrDefault(emptyList())
        return AutoRules(p.getBoolean("enabled", false), motion, places)
    }

    fun update(context: Context, rules: AutoRules) {
        val places = JSONArray(
            rules.places.map {
                JSONObject().put("id", it.id).put("name", it.name).put("lat", it.latitude).put("lng", it.longitude).put("mode", it.mode?.code ?: -1)
            },
        )
        prefs(context).edit().apply {
            putBoolean("enabled", rules.enabled)
            rules.motion.forEach { (m, mode) -> putInt("motion_${m.name}", mode?.code ?: -1) }
            putString("places", places.toString())
            // Places that are gone can't be "inside" any more.
            putStringSet("inside", inside(context).filter { id -> rules.places.any { it.id == id } }.toSet())
        }.apply()
        _rules.value = rules
        register(context)
    }

    fun newPlace(name: String, latitude: Double, longitude: Double, mode: SoundMode?) =
        Place(UUID.randomUUID().toString(), name, latitude, longitude, mode)

    private fun inside(context: Context): Set<String> = prefs(context).getStringSet("inside", emptySet()).orEmpty()

    private fun motion(context: Context): Motion? = prefs(context).getString("motion", null)?.let { runCatching { Motion.valueOf(it) }.getOrNull() }

    /** (Re)registers with Android for the events the rules need; called on changes and after a restart. */
    fun register(context: Context) {
        val app = context.applicationContext
        val rules = load(app)
        runCatching { Detection.stop(app) }
        if (!rules.enabled) return
        if (Detection.CAN_DETECT_MOTION && hasMotionAccess(app) && rules.motion.values.any { it != null }) {
            runCatching { Detection.watchMotion(app) }
        }
        val places = rules.places.filter { it.mode != null }
        if (places.isNotEmpty() && FindMy.hasLocationAccess(app)) runCatching { Detection.watchPlaces(app, places) }
    }

    /**
     * A broadcast to [AutoModeReceiver]. Android adds its results to the intent, so it must be
     * mutable; [extra] (a place's ID) keeps one place's intent apart from another's.
     */
    internal fun pendingIntent(context: Context, action: String, extra: String? = null) = PendingIntent.getBroadcast(
        context, (action + extra.orEmpty()).hashCode(),
        Intent(context, AutoModeReceiver::class.java).setAction(action).apply { extra?.let { putExtra(EXTRA_PLACE, it) } },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    internal const val EXTRA_PLACE = "place"

    internal fun onEvent(context: Context, intent: Intent): Boolean {
        val p = prefs(context)
        when (intent.action) {
            ACTION_MOTION -> {
                val motion = Detection.motion(intent) ?: return false
                p.edit().putString("motion", motion.name).apply()
            }
            ACTION_PLACE -> {
                val (entered, ids) = Detection.placeChange(intent) ?: return false
                p.edit().putStringSet("inside", if (entered) inside(context) + ids else inside(context) - ids).apply()
            }
            else -> return false
        }
        return true
    }

    /** Applies the rules now, if the earbuds are connected to this phone for audio. */
    suspend fun applyNow(context: Context) {
        val app = context.applicationContext
        if (!isOn(app) || EarbudsAudio.output(app) == null) return
        runCatching {
            withTimeout(20_000) { EarbudsLink.use(app) { c -> apply(app, c, c.readSoundMode()) } }
        }
    }

    /** Called when the earbuds have just connected, on a connection that's already open. */
    suspend fun applyOnConnect(context: Context, c: EarbudClient, current: SoundMode?) {
        if (isOn(context)) apply(context, c, current)
    }

    private suspend fun apply(context: Context, c: EarbudClient, current: SoundMode?) {
        val (mode, why) = load(context).pick(inside(context), motion(context)) ?: return
        val p = prefs(context)
        val now = System.currentTimeMillis()
        if (mode == current || now - p.getLong("last_switch", 0) < MIN_GAP_MS) return
        c.writeSoundMode(mode)
        p.edit().putLong("last_switch", now).apply()
        TileState.save(context, mode = mode)
        Notifications.post(
            context, Notifications.Kind.AUTO_MODE,
            Notifications.builder(context, Notifications.Kind.AUTO_MODE)
                .setContentTitle("${mode.label} on")
                .setContentText(why)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setTimeoutAfter(10_000)
                .build(),
        )
    }
}

/** Activity changes and place arrivals from Android, even while the app is closed. */
class AutoModeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!AutoModes.onEvent(context, intent)) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                AutoModes.applyNow(context)
            } finally {
                pending.finish()
            }
        }
    }
}

/** Activity recognition and geofences don't survive a restart or an app update; register again. */
class RestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            AutoModes.register(context)
        }
    }
}
