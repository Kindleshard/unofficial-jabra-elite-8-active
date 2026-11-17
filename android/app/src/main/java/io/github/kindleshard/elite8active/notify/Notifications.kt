package io.github.kindleshard.elite8active.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.kindleshard.elite8active.MainActivity
import io.github.kindleshard.elite8active.R

/** The app's notification channels, and posting to them. */
object Notifications {
    /** Each notification has its own ID, so they replace themselves instead of piling up. */
    enum class Kind(val channel: String, val title: String, val description: String, val importance: Int) {
        CONNECTED("connected", "Battery when connecting", "The earbuds' battery, shown briefly when they connect", NotificationManager.IMPORTANCE_HIGH),
        LOW_BATTERY("battery_low", "Low battery", "When an earbud or the case is almost empty", NotificationManager.IMPORTANCE_HIGH),
        LEFT_BEHIND("left_behind", "Left behind", "When the earbuds may have been left behind", NotificationManager.IMPORTANCE_HIGH),
        FIRMWARE("firmware", "Firmware updates", "When Jabra publishes new firmware for the earbuds", NotificationManager.IMPORTANCE_DEFAULT),
        AUTO_MODE("auto_modes", "Automatic modes", "A short note when the sound mode changes by itself", NotificationManager.IMPORTANCE_LOW),
        ;

        val id: Int get() = 100 + ordinal
    }

    fun allowed(context: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun builder(context: Context, kind: Kind): NotificationCompat.Builder {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(
            NotificationChannel(kind.channel, kind.title, kind.importance).apply {
                description = kind.description
                // The pop-up should appear without a sound; the others keep the phone's defaults.
                if (kind == Kind.CONNECTED) {
                    setSound(null, null)
                    enableVibration(false)
                }
            },
        )
        return NotificationCompat.Builder(context, kind.channel)
            .setSmallIcon(R.drawable.ic_stat_earbuds)
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
    }

    fun post(context: Context, kind: Kind, notification: Notification) {
        if (!allowed(context)) return
        runCatching { context.getSystemService(NotificationManager::class.java)?.notify(kind.id, notification) }
    }

    fun cancel(context: Context, kind: Kind) {
        context.getSystemService(NotificationManager::class.java)?.cancel(kind.id)
    }

    fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Whether the app's screen is showing right now (set by MainActivity); no pop-ups over it. */
    @Volatile
    var appVisible = false
}
