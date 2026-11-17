package io.github.kindleshard.elite8active.findmy

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/** The earbuds as an Android audio output, if they're connected. */
object EarbudsAudio {
    private val MEDIA_TYPES = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER)

    fun output(context: Context, includeCalls: Boolean = false): AudioDeviceInfo? {
        val types = if (includeCalls) MEDIA_TYPES + AudioDeviceInfo.TYPE_BLUETOOTH_SCO else MEDIA_TYPES
        val outputs = context.getSystemService(AudioManager::class.java)
            ?.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty()
            .filter { it.type in types }
        return outputs.firstOrNull { it.productName?.contains("Jabra", ignoreCase = true) == true } ?: outputs.firstOrNull()
    }
}

/**
 * "Play sound" for finding the earbuds: a loud repeating chirp sent only to the earbuds (never
 * the phone speaker), at full media volume, for up to a minute. The volume is put back afterwards.
 */
class LocatorSound(context: Context) {
    private val app = context.applicationContext
    private val audio = app.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var track: AudioTrack? = null
    private var focus: AudioFocusRequest? = null
    private var savedVolume: Int? = null
    var onStopped: () -> Unit = {}

    val playing: Boolean get() = track != null

    /** Returns false if the earbuds aren't connected as an audio output. */
    fun start(durationMs: Long = 60_000): Boolean {
        if (playing) return true
        val device = EarbudsAudio.output(app) ?: return false
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val pcm = chirp()
        val t = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(
                AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        t.write(pcm, 0, pcm.size)
        t.setLoopPoints(0, pcm.size, -1)
        t.setPreferredDevice(device)
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attributes).build()
            .also { audio.requestAudioFocus(it) }
        savedVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0) }
        t.play()
        track = t
        handler.postDelayed(::stop, durationMs)
        return true
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        val t = track ?: return
        track = null
        runCatching { t.stop() }
        t.release()
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
        savedVolume?.let { v -> runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0) } }
        savedVolume = null
        onStopped()
    }

    /** Three rising beeps, then a short pause; looped. */
    private fun chirp(): ShortArray {
        val beep = (RATE * 0.12).toInt()
        val gap = (RATE * 0.06).toInt()
        val pause = (RATE * 0.5).toInt()
        val fade = RATE / 200
        val out = ShortArray(3 * (beep + gap) + pause)
        listOf(2000.0, 2600.0, 3200.0).forEachIndexed { n, freq ->
            val start = n * (beep + gap)
            for (i in 0 until beep) {
                val envelope = min(1.0, min(i, beep - 1 - i) / fade.toDouble())
                out[start + i] = (sin(2 * PI * freq * i / RATE) * envelope * Short.MAX_VALUE * 0.9).toInt().toShort()
            }
        }
        return out
    }

    private companion object {
        const val RATE = 48_000
    }
}
