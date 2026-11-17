package io.github.kindleshard.elite8active.findmy

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.kindleshard.elite8active.notify.Notifications
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * "Left behind?" alerts, on top of Find My: when the earbuds disconnect and, within the next few
 * minutes, the phone moves away from where that happened while they stay disconnected.
 *
 * The earbuds can't tell the phone whether they went into the case, so packing them away and
 * leaving straight after can also trigger it. Moving away only counts soon after the disconnect,
 * which keeps that to the times you pack up and leave at once.
 */
object LeftBehind {
    private const val WORK = "left_behind"
    /** Further than this from where they disconnected, beyond what the location fixes allow for. */
    const val AWAY_METRES = 120.0
    private val CHECK_AFTER_MINUTES = listOf(4L, 10L)

    private fun prefs(context: Context) = context.getSharedPreferences("left_behind", Context.MODE_PRIVATE)

    fun isOn(context: Context) = prefs(context).getBoolean("enabled", false)

    fun setOn(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("enabled", on).apply()
        if (!on) WorkManager.getInstance(context).cancelUniqueWork(WORK)
    }

    /** Called with the place the earbuds just disconnected; every link dropping replaces the last check. */
    fun watch(context: Context, s: Sighting, attempt: Int = 0) {
        if (!isOn(context) || attempt >= CHECK_AFTER_MINUTES.size) return
        val data = workDataOf("lat" to s.latitude, "lng" to s.longitude, "acc" to s.accuracy, "time" to s.time, "attempt" to attempt)
        // A new disconnect replaces any check in progress; a follow-up waits for the current one.
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK, if (attempt == 0) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<LeftBehindWorker>()
                .setInitialDelay(CHECK_AFTER_MINUTES[attempt] - CHECK_AFTER_MINUTES.getOrElse(attempt - 1) { 0 }, TimeUnit.MINUTES)
                .setInputData(data)
                .build(),
        )
    }

    /** Far enough to count as having walked away, given how accurate both fixes are. */
    fun movedAway(from: Sighting, now: Sighting) = from.distanceTo(now) - maxOf(from.accuracy, now.accuracy) > AWAY_METRES

    internal fun notify(context: Context, s: Sighting) {
        val place = FindMy.placeName(context, s) ?: "where they disconnected"
        val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(s.time))
        val map = Intent(Intent.ACTION_VIEW, Uri.parse("geo:${s.latitude},${s.longitude}?q=${s.latitude},${s.longitude}(Earbuds)"))
        Notifications.post(
            context, Notifications.Kind.LEFT_BEHIND,
            Notifications.builder(context, Notifications.Kind.LEFT_BEHIND)
                .setContentTitle("Did you leave your earbuds behind?")
                .setContentText("They disconnected near $place at $time and haven't come back.")
                .setStyle(NotificationCompat.BigTextStyle().bigText("They disconnected near $place at $time and haven't come back. If they're in the case with you, ignore this."))
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .addAction(0, "Show on map", PendingIntent.getActivity(context, 3, map, PendingIntent.FLAG_IMMUTABLE))
                .build(),
        )
    }
}

class LeftBehindWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext
        if (!LeftBehind.isOn(app)) return Result.success()
        val d = inputData
        val from = Sighting("", d.getDouble("lat", 0.0), d.getDouble("lng", 0.0), d.getFloat("acc", 0f), d.getLong("time", 0))
        // Back with you: nothing to say.
        if (EarbudsAudio.output(app, includeCalls = true) != null) return Result.success()
        val location = FindMy.currentLocation(app) ?: return Result.success()
        val now = Sighting("", location.latitude, location.longitude, location.accuracy, System.currentTimeMillis())
        if (LeftBehind.movedAway(from, now)) {
            LeftBehind.notify(app, from)
        } else {
            LeftBehind.watch(app, from, d.getInt("attempt", 0) + 1)
        }
        return Result.success()
    }
}
