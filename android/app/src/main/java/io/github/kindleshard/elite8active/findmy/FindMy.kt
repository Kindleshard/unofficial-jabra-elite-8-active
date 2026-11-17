package io.github.kindleshard.elite8active.findmy

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import io.github.kindleshard.elite8active.bt.EarbudsLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Where the phone was when one of the earbuds' Bluetooth links dropped. */
data class Sighting(
    val address: String,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val time: Long,
) {
    fun encode() = "$latitude,$longitude,$accuracy,$time"

    /** Great-circle distance in metres. */
    fun distanceTo(o: Sighting): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(o.latitude - latitude)
        val dLng = Math.toRadians(o.longitude - longitude)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(latitude)) * cos(Math.toRadians(o.latitude)) * sin(dLng / 2).pow(2)
        return 2 * r * asin(sqrt(a))
    }

    companion object {
        fun decode(address: String, text: String): Sighting? {
            val p = text.split(',')
            if (p.size != 4) return null
            return Sighting(address, p[0].toDoubleOrNull() ?: return null, p[1].toDoubleOrNull() ?: return null, p[2].toFloatOrNull() ?: 0f, p[3].toLongOrNull() ?: return null)
        }
    }
}

data class FindMyState(val enabled: Boolean = false, val sightings: List<Sighting> = emptyList()) {
    /**
     * One entry per place, newest first. Each earbud has its own Bluetooth link (and address), so
     * when both drop together they land in the same place; an earbud lost on its own shows up
     * as a second place.
     */
    val places: List<Sighting>
        get() = sightings.sortedByDescending { it.time }.fold(emptyList()) { kept, s ->
            if (kept.any { it.distanceTo(s) < SAME_PLACE_METRES }) kept else kept + s
        }

    companion object {
        const val SAME_PLACE_METRES = 100.0
    }
}

/**
 * Find My: when the earbuds disconnect, save where the phone is. Only the latest location per
 * earbud link is kept, on this phone only (like Sound+'s Find My Jabra).
 */
object FindMy {
    private const val TAG = "Elite8Active"
    private const val KEY_ENABLED = "enabled"
    private const val SIGHTING_PREFIX = "sighting_"

    private lateinit var prefs: SharedPreferences
    private val _state = MutableStateFlow(FindMyState())
    val state: StateFlow<FindMyState> = _state

    // Held here because SharedPreferences only keeps a weak reference to its listeners.
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> reload() }

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences("find_my", Context.MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(listener)
        reload()
    }

    fun setEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()

    fun record(s: Sighting) = prefs.edit().putString(SIGHTING_PREFIX + s.address, s.encode()).apply()

    fun clear() = prefs.edit().apply { prefs.all.keys.filter { it.startsWith(SIGHTING_PREFIX) }.forEach(::remove) }.apply()

    private fun reload() {
        val sightings = prefs.all.mapNotNull { (key, value) ->
            if (key.startsWith(SIGHTING_PREFIX) && value is String) Sighting.decode(key.removePrefix(SIGHTING_PREFIX), value) else null
        }
        _state.value = FindMyState(prefs.getBoolean(KEY_ENABLED, false), sightings)
    }

    fun hasForegroundLocation(context: Context) =
        granted(context, Manifest.permission.ACCESS_FINE_LOCATION) || granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)

    /** Needed because the earbuds usually disconnect while this app is closed. */
    fun hasLocationAccess(context: Context) =
        hasForegroundLocation(context) && granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** A fresh fix if one comes within [timeoutMs], otherwise the newest location the phone already has. */
    @SuppressLint("MissingPermission")
    suspend fun currentLocation(context: Context, timeoutMs: Long = 20_000): Location? {
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        if (!lm.isLocationEnabled) return null
        val providers = listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { lm.hasProvider(it) }
        val fresh = providers.firstOrNull()?.let { provider ->
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    val signal = CancellationSignal()
                    cont.invokeOnCancellation { signal.cancel() }
                    lm.getCurrentLocation(provider, signal, context.mainExecutor) { cont.resume(it) }
                }
            }
        }
        return fresh ?: providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
    }

    /** Saves the phone's location for a link that just dropped. Runs while the app is closed too. */
    suspend fun recordDisconnect(context: Context, address: String): Sighting? {
        val location = currentLocation(context)
        if (location == null) {
            Log.w(TAG, "find my: no location for ${address.takeLast(5)}")
            return null
        }
        val sighting = Sighting(address, location.latitude, location.longitude, location.accuracy, System.currentTimeMillis())
        record(sighting)
        Log.i(TAG, "find my: saved location for ${address.takeLast(5)} (±${location.accuracy.toInt()} m)")
        return sighting
    }

    /** A street address for a place, from the phone's geocoder; slow, so call off the main thread. */
    fun placeName(context: Context, s: Sighting): String? {
        if (!Geocoder.isPresent()) return null
        return runCatching {
            @Suppress("DEPRECATION")
            Geocoder(context).getFromLocation(s.latitude, s.longitude, 1)?.firstOrNull()?.getAddressLine(0)
        }.getOrNull()
    }
}

/** Woken by Android when any Bluetooth link drops, even while the app is closed. */
class BluetoothLinkReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_DISCONNECTED) return
        val device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
        if (!EarbudsLink.isEarbuds(context, device)) return
        FindMy.init(context)
        if (!FindMy.state.value.enabled || !FindMy.hasLocationAccess(context)) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                FindMy.recordDisconnect(context.applicationContext, device.address)?.let { LeftBehind.watch(context.applicationContext, it) }
            } finally {
                pending.finish()
            }
        }
    }
}
