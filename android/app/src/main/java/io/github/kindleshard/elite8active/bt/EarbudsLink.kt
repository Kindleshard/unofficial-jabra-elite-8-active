package io.github.kindleshard.elite8active.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/**
 * The earbuds allow one control connection per phone, so everything in this app shares it:
 * the screen holds [active] while it's visible, and the Quick Settings tile borrows that
 * connection or, if there is none, opens one just long enough for its request.
 */
object EarbudsLink {
    /** The screen's connection, while it has one. */
    @Volatile
    var active: EarbudClient? = null

    /** Serialises connection attempts, so the screen and the tile never race for the channel. */
    val connectLock = Mutex()

    /** Outlives a tile or screen, so a change that's been started still finishes. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun prefs(context: Context) = context.getSharedPreferences("link", Context.MODE_PRIVATE)

    /** Remembers the earbuds' address once they've answered, so they're still found after a rename. */
    fun remember(context: Context, device: BluetoothDevice) =
        prefs(context).edit().putString("address", device.address).apply()

    /** A Jabra device by its Bluetooth name, or the earbuds this app has connected to before. */
    @SuppressLint("MissingPermission")
    fun isEarbuds(context: Context, device: BluetoothDevice) = try {
        device.name?.contains("Jabra", ignoreCase = true) == true || device.address == prefs(context).getString("address", null)
    } catch (_: SecurityException) {
        false
    }

    /**
     * Paired earbuds to try. They can be bonded under more than one address (e.g. an LE Audio
     * bond to the left earbud); only the classic address serves RFCOMM, so classic-capable ones
     * come first, and the address that worked last time before those.
     */
    @SuppressLint("MissingPermission")
    fun candidates(context: Context): List<BluetoothDevice> {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) throw IOException("Bluetooth is off.")
        val known = prefs(context).getString("address", null)
        return adapter.bondedDevices.orEmpty()
            .filter { isEarbuds(context, it) }
            .sortedWith(compareBy({ it.address != known }, { it.type == BluetoothDevice.DEVICE_TYPE_LE }))
    }

    /** Runs [block] on the screen's connection if there is one, otherwise on a short-lived one. */
    suspend fun <T> use(context: Context, block: suspend (EarbudClient) -> T): T {
        active?.takeIf { it.connected.value }?.let { return block(it) }
        return connectLock.withLock {
            active?.takeIf { it.connected.value }?.let { return@withLock block(it) }
            var failure: Exception = IOException("No paired Jabra earbuds found.")
            for (device in candidates(context)) {
                val client = EarbudClient(device)
                try {
                    client.connect()
                } catch (e: Exception) {
                    client.close()
                    if (e is CancellationException) throw e
                    // Keep the clearest reason: a wrong model says more than an unreachable one.
                    if (failure !is UnsupportedModelException) failure = e
                    continue
                }
                remember(context, device)
                return@withLock client.use { block(it) }
            }
            throw failure
        }
    }
}
