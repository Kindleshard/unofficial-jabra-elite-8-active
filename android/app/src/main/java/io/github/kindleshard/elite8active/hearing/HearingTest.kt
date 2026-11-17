package io.github.kindleshard.elite8active.hearing

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import io.github.kindleshard.elite8active.findmy.EarbudsAudio
import io.github.kindleshard.elite8active.gnp.Jabra
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A simple app-side hearing check (Sound+'s MySound isn't offered for these earbuds): for each EQ
 * band and each ear, find the quietest tone you notice, then turn the differences into an EQ.
 * Not calibrated and not a medical test; it only compares bands with each other.
 */
object HearingProfile {
    /** One test tone per EQ band (Bass, Low mid, Mid, High mid, Treble). */
    val FREQUENCIES = listOf(250.0, 750.0, 3000.0, 8000.0, 12000.0)

    /**
     * How much louder a typical young listener needs each tone than the 3 kHz one (ISO 226 threshold
     * of hearing, rounded). A result with this shape means "typical hearing" and gives a flat EQ.
     */
    private val TYPICAL = listOf(16.0, 8.0, 0.0, 18.0, 16.0)

    /**
     * EQ gains from the thresholds (dB below full scale, per band, per ear). Bands you hear less
     * well than the others get up to half the difference back (the half-gain rule hearing aids
     * start from), within the earbuds' +4.5 limit.
     */
    fun eqGains(left: List<Double>, right: List<Double>): List<Int> {
        val loss = FREQUENCIES.indices.map { (left[it] + right[it]) / 2 - TYPICAL[it] }
        val best = loss.min()
        return loss.map { ((it - best) * 0.5).coerceIn(0.0, Jabra.EQ_GAIN_MAX / 100.0) }
            .map { (it * 100).roundToInt() }
    }
}

/** Plays rising, pulsed tones in one ear through the earbuds and reports the level you tapped at. */
class ToneTester(context: Context) {
    private val app = context.applicationContext
    private val audio = app.getSystemService(AudioManager::class.java)
    private val heard = AtomicBoolean(false)
    private var savedVolume: Int? = null
    private var focus: AudioFocusRequest? = null

    /** Same media volume for every tone, so the bands can be compared. */
    fun begin(): Boolean {
        if (EarbudsAudio.output(app) == null) return false
        savedVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, (max * 0.6).roundToInt(), 0) }
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(ATTRIBUTES).build()
            .also { audio.requestAudioFocus(it) }
        return true
    }

    fun end() {
        savedVolume?.let { v -> runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0) } }
        savedVolume = null
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
    }

    /** Called from the "I hear it" button. */
    fun tap() = heard.set(true)

    /** Returns the level in dBFS when the user tapped (or [MAX_DB] if they never did). */
    suspend fun threshold(frequency: Double, leftEar: Boolean): Double = withContext(Dispatchers.Default) {
        heard.set(false)
        val chunk = RATE / 100 // 10 ms
        val track = AudioTrack.Builder()
            .setAudioAttributes(ATTRIBUTES)
            .setAudioFormat(
                AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(chunk * 2 * 4 * 4)
            .build()
        EarbudsAudio.output(app)?.let { track.setPreferredDevice(it) }
        track.play()
        val buffer = FloatArray(chunk * 2)
        var sample = 0L
        var level = START_DB
        try {
            while (isActive && !heard.get() && level < MAX_DB) {
                val amplitude = 10.0.pow(level / 20)
                for (i in 0 until chunk) {
                    val t = sample % PULSE
                    // 300 ms tone, 200 ms silence, with 15 ms fades so there are no clicks.
                    val envelope = if (t < ON) min(1.0, min(t, ON - t) / FADE.toDouble()) else 0.0
                    val v = (sin(2 * PI * frequency * sample / RATE) * amplitude * envelope).toFloat()
                    buffer[2 * i] = if (leftEar) v else 0f
                    buffer[2 * i + 1] = if (leftEar) 0f else v
                    sample++
                }
                track.write(buffer, 0, buffer.size, AudioTrack.WRITE_BLOCKING)
                level += RISE_DB_PER_S / 100
            }
        } finally {
            runCatching { track.stop() }
            track.release()
        }
        // People react about 0.4 s after they hear it.
        if (heard.get()) level - RISE_DB_PER_S * 0.4 else MAX_DB
    }

    companion object {
        private const val RATE = 48_000
        private const val PULSE = RATE / 2L
        private const val ON = RATE * 3 / 10L
        private const val FADE = RATE * 15 / 1000L
        const val START_DB = -78.0
        const val MAX_DB = -18.0
        private const val RISE_DB_PER_S = 4.0
        private val ATTRIBUTES = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    }
}
