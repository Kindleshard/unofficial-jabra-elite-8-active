package io.github.kindleshard.elite8active.calls

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.kindleshard.elite8active.MainActivity
import io.github.kindleshard.elite8active.R
import io.github.kindleshard.elite8active.findmy.EarbudsAudio

/**
 * Quick access to call settings (as in Sound+): while a call is going on with the earbuds
 * connected, an "On a call" notification opens the app straight at the call settings.
 */
object CallAccess {
    const val EXTRA_OPEN = "open"
    const val OPEN_CALL = "call"
    private const val CHANNEL = "calls"
    private const val NOTIFICATION_ID = 1
    private const val KEY_ENABLED = "quick_access"

    private fun prefs(context: Context) = context.getSharedPreferences("calls", Context.MODE_PRIVATE)

    fun isEnabled(context: Context) = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (!enabled) hide(context)
    }

    /** READ_PHONE_STATE is what lets Android tell this app about calls while it's closed. */
    val PERMISSIONS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.POST_NOTIFICATIONS)
    } else {
        arrayOf(Manifest.permission.READ_PHONE_STATE)
    }

    fun hasPermissions(context: Context) = PERMISSIONS.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    fun isCallMode(mode: Int) = mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION

    fun show(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Call settings", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Quick access to the earbuds' call settings during a call"
            },
        )
        val open = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_OPEN, OPEN_CALL)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_earbuds)
            .setContentTitle("On a call")
            .setContentText("Tap for your earbuds' call settings")
            .setContentIntent(PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    fun hide(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }
}

/** Woken by Android on every call state change, even while the app is closed. */
class CallStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
            TelephonyManager.EXTRA_STATE_OFFHOOK ->
                if (CallAccess.isEnabled(context) && EarbudsAudio.output(context, includeCalls = true) != null) CallAccess.show(context)
            TelephonyManager.EXTRA_STATE_IDLE -> CallAccess.hide(context)
        }
    }
}
