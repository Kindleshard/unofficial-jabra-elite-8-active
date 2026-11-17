package io.github.kindleshard.elite8active.automodes

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.location.LocationManager

/**
 * Places through Android's own location service (the "fdroid" build, which uses only free
 * libraries). Android has no activity recognition of its own, so the rules for what you're doing
 * aren't offered here; the "full" build gets them from Google Play services.
 */
object Detection {
    /** Whether this build can tell what you're doing (walking, in a vehicle…). */
    const val CAN_DETECT_MOTION = false

    private fun prefs(context: Context) = context.getSharedPreferences("auto_modes_proximity", Context.MODE_PRIVATE)

    private fun placeIntent(context: Context, id: String) = AutoModes.pendingIntent(context, AutoModes.ACTION_PLACE, id)

    fun stop(context: Context) {
        val lm = context.getSystemService(LocationManager::class.java) ?: return
        val watched = prefs(context).getStringSet("ids", emptySet()).orEmpty()
        watched.forEach { lm.removeProximityAlert(placeIntent(context, it)) }
        prefs(context).edit().remove("ids").apply()
    }

    fun watchMotion(context: Context) = Unit

    /** One proximity alert per place; Android checks them now and then, so arrivals can take a few minutes to show. */
    @SuppressLint("MissingPermission")
    fun watchPlaces(context: Context, places: List<Place>) {
        val lm = context.getSystemService(LocationManager::class.java) ?: return
        places.forEach { lm.addProximityAlert(it.latitude, it.longitude, AutoModes.PLACE_RADIUS_M, -1, placeIntent(context, it.id)) }
        prefs(context).edit().putStringSet("ids", places.map { it.id }.toSet()).apply()
    }

    fun motion(intent: Intent): Motion? = null

    /** Whether you arrived (true) or left (false), and which place, from a proximity alert. */
    fun placeChange(intent: Intent): Pair<Boolean, Set<String>>? {
        if (!intent.hasExtra(LocationManager.KEY_PROXIMITY_ENTERING)) return null
        val id = intent.getStringExtra(AutoModes.EXTRA_PLACE) ?: return null
        return intent.getBooleanExtra(LocationManager.KEY_PROXIMITY_ENTERING, false) to setOf(id)
    }
}
