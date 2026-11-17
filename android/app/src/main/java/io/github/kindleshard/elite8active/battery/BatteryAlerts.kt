package io.github.kindleshard.elite8active.battery

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.kindleshard.elite8active.automodes.AutoModes
import io.github.kindleshard.elite8active.bt.EarbudsLink
import io.github.kindleshard.elite8active.bt.readBattery
import io.github.kindleshard.elite8active.bt.readSoundMode
import io.github.kindleshard.elite8active.findmy.EarbudsAudio
import io.github.kindleshard.elite8active.gnp.Battery
import io.github.kindleshard.elite8active.gnp.SoundMode
import io.github.kindleshard.elite8active.notify.Notifications
import io.github.kindleshard.elite8active.tile.TileState
import kotlinx.coroutines.withTimeout
import java.util.concurrent.TimeUnit

/** Where every fresh battery reading goes, whichever part of the app read it. Call off the main thread. */
object BatteryReports {
    fun report(context: Context, battery: Battery, mode: SoundMode?) {
        TileState.save(context, mode, battery)
        BatteryLog.record(context, battery, mode)
        BatteryAlerts.check(context, battery)
    }
}

/** The battery pop-up when the earbuds connect, and the low-battery alert. */
object BatteryAlerts {
    /** Alert at this level or below, once until the part has charged past [CHARGED]. */
    const val LOW = 10
    private const val CHARGED = 20
    private const val KEY_POPUP = "popup"
    private const val KEY_LOW = "low"

    private fun prefs(context: Context) = context.getSharedPreferences("alerts", Context.MODE_PRIVATE)

    fun isPopupOn(context: Context) = prefs(context).getBoolean(KEY_POPUP, false)
    fun setPopup(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY_POPUP, on).apply()

    fun isLowAlertOn(context: Context) = prefs(context).getBoolean(KEY_LOW, false)

    fun setLowAlert(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_LOW, on).apply()
        BatteryLog.updateChecks(context)
    }

    @Synchronized
    fun check(context: Context, battery: Battery) {
        if (!isLowAlertOn(context)) return
        val p = prefs(context)
        val newlyLow = listOf("Left earbud" to battery.left, "Right earbud" to battery.right, "Case" to battery.case).filter { (part, level) ->
            val key = "warned_$part"
            when {
                level == null -> false
                level <= LOW && !p.getBoolean(key, false) -> true.also { p.edit().putBoolean(key, true).apply() }
                level >= CHARGED -> false.also { p.edit().putBoolean(key, false).apply() }
                else -> false
            }
        }
        if (newlyLow.isEmpty()) return
        val title = if (newlyLow.size == 1) "${newlyLow[0].first} battery low" else "Earbuds battery low"
        val text = newlyLow.joinToString(" · ") { (part, level) -> "$part $level%" } +
            if (newlyLow.any { it.first == "Case" }) ". Charge the case soon." else ". Put them in the case to charge."
        Notifications.post(
            context, Notifications.Kind.LOW_BATTERY,
            Notifications.builder(context, Notifications.Kind.LOW_BATTERY)
                .setContentTitle(title)
                .setContentText(text)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .build(),
        )
    }

    fun showPopup(context: Context, battery: Battery, name: String?) {
        val text = listOfNotNull(
            battery.left?.let { "Left $it%" } ?: "Left offline",
            battery.right?.let { "Right $it%" } ?: "Right offline",
            battery.case?.let { "Case $it%" },
        ).joinToString(" · ")
        Notifications.post(
            context, Notifications.Kind.CONNECTED,
            Notifications.builder(context, Notifications.Kind.CONNECTED)
                .setContentTitle(name ?: "Earbuds connected")
                .setContentText(text)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setTimeoutAfter(POPUP_MS)
                .build(),
        )
    }

    private const val POPUP_MS = 12_000L
}

/**
 * Woken by Android when any Bluetooth link opens, even while the app is closed. The earbuds open
 * several (one per earbud and transport), so the work is queued once, a few seconds later, when
 * the control channel is ready.
 */
class EarbudsConnectedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
        val device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
        if (!EarbudsLink.isEarbuds(context, device)) return
        if (!BatteryAlerts.isPopupOn(context) && !BatteryAlerts.isLowAlertOn(context) && !AutoModes.isOn(context)) return
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<EarbudsConnectedWorker>().setInitialDelay(5, TimeUnit.SECONDS).build(),
        )
    }

    companion object {
        private const val WORK = "earbuds_connected"
    }
}

/** Reads the battery once the earbuds are up: the pop-up, low-battery check and automatic mode. */
class EarbudsConnectedWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    @SuppressLint("MissingPermission")
    override suspend fun doWork(): Result {
        val app = applicationContext
        // Only for a real connection (audio up), not a link that opened and closed again.
        if (EarbudsAudio.output(app, includeCalls = true) == null) return Result.success()
        runCatching {
            withTimeout(20_000) {
                EarbudsLink.use(app) { c ->
                    val battery = c.readBattery()
                    val mode = c.readSoundMode()
                    BatteryReports.report(app, battery, mode)
                    if (BatteryAlerts.isPopupOn(app) && !Notifications.appVisible) {
                        BatteryAlerts.showPopup(app, battery, EarbudsAudio.output(app, includeCalls = true)?.productName?.toString())
                    }
                    AutoModes.applyOnConnect(app, c, mode)
                }
            }
        }
        return Result.success()
    }
}
