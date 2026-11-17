package io.github.kindleshard.elite8active.firmware

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.kindleshard.elite8active.EarbudsViewModel
import io.github.kindleshard.elite8active.notify.Notifications
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Tells you when Jabra publishes newer firmware than the earbuds have. Sound+ asks Jabra's
 * firmware service, which needs Sound+'s own API key, so this reads Jabra's public release-notes
 * page instead (it lists every version). Updating itself is still done in Sound+.
 */
object FirmwareCheck {
    const val RELEASE_NOTES = "https://www.jabra.com/support/release-notes/release-note-jabra-elite-8-active"
    private const val WORK = "firmware_check"
    private const val NOW = "firmware_check_now"

    private fun prefs(context: Context) = context.getSharedPreferences("firmware", Context.MODE_PRIVATE)

    fun isOn(context: Context) = prefs(context).getBoolean("enabled", false)

    fun setOn(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("enabled", on).apply()
        val work = WorkManager.getInstance(context)
        if (on) {
            val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            work.enqueueUniquePeriodicWork(
                WORK, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<FirmwareWorker>(7, TimeUnit.DAYS).setConstraints(online).build(),
            )
            work.enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<FirmwareWorker>().setConstraints(online).build())
        } else {
            work.cancelUniqueWork(WORK)
        }
    }

    /** The version the earbuds report, saved whenever the app reads it. */
    fun rememberInstalled(context: Context, version: String) = prefs(context).edit().putString("installed", version).apply()

    fun installed(context: Context): String? = prefs(context).getString("installed", null)

    /** The newest version on Jabra's page at the last check, if there was one. */
    fun latestKnown(context: Context): String? = prefs(context).getString("latest", null)

    /** Versions appear on the page as their own elements, e.g. `>4.6.0<`. */
    fun versionsIn(html: String): List<String> =
        Regex(""">\s*(\d{1,2}\.\d{1,2}\.\d{1,3})\s*<""").findAll(html).map { it.groupValues[1] }.distinct().toList()

    fun isNewer(candidate: String, than: String): Boolean {
        val a = candidate.split('.').map { it.toIntOrNull() ?: 0 }
        val b = than.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    fun newest(versions: List<String>): String? = versions.reduceOrNull { best, v -> if (isNewer(v, best)) v else best }

    suspend fun check(context: Context) {
        val installed = installed(context) ?: return
        val latest = newest(versionsIn(fetch(RELEASE_NOTES))) ?: return
        val p = prefs(context)
        p.edit().putString("latest", latest).apply()
        if (!isNewer(latest, installed) || p.getString("notified", null) == latest) return
        p.edit().putString("notified", latest).apply()
        val soundPlus = context.packageManager.getLaunchIntentForPackage(EarbudsViewModel.SOUND_PLUS)
        val notes = Intent(Intent.ACTION_VIEW, Uri.parse(RELEASE_NOTES))
        Notifications.post(
            context, Notifications.Kind.FIRMWARE,
            Notifications.builder(context, Notifications.Kind.FIRMWARE)
                .setContentTitle("Firmware $latest is available")
                .setContentText("Your earbuds have $installed. " + if (soundPlus != null) "Update them in Jabra Sound+." else "Updates are installed with Jabra Sound+.")
                .setContentIntent(PendingIntent.getActivity(context, 1, soundPlus ?: notes, PendingIntent.FLAG_IMMUTABLE))
                .addAction(0, "Release notes", PendingIntent.getActivity(context, 2, notes, PendingIntent.FLAG_IMMUTABLE))
                .build(),
        )
    }

    /** The page redirects to a regional copy and sets cookies on the way, so both are followed by hand. */
    private suspend fun fetch(url: String): String = withContext(Dispatchers.IO) {
        var next = URL(url)
        val cookies = mutableMapOf<String, String>()
        repeat(8) {
            val c = next.openConnection() as HttpURLConnection
            try {
                c.instanceFollowRedirects = false
                c.connectTimeout = 15_000
                c.readTimeout = 15_000
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) Elite8Active")
                if (cookies.isNotEmpty()) c.setRequestProperty("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
                c.headerFields["Set-Cookie"].orEmpty().forEach { header ->
                    header.substringBefore(';').split('=', limit = 2).takeIf { it.size == 2 }?.let { (k, v) -> cookies[k.trim()] = v.trim() }
                }
                when (c.responseCode) {
                    in 300..399 -> next = URL(next, c.getHeaderField("Location") ?: return@withContext "")
                    in 200..299 -> return@withContext c.inputStream.bufferedReader().use { it.readText() }
                    else -> return@withContext ""
                }
            } finally {
                c.disconnect()
            }
        }
        ""
    }
}

class FirmwareWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!FirmwareCheck.isOn(applicationContext)) return Result.success()
        return if (runCatching { FirmwareCheck.check(applicationContext) }.isSuccess) Result.success() else Result.retry()
    }
}
