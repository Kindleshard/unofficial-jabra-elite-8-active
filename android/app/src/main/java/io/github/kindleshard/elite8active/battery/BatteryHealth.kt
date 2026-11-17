package io.github.kindleshard.elite8active.battery

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.kindleshard.elite8active.bt.EarbudsLink
import io.github.kindleshard.elite8active.bt.readBattery
import io.github.kindleshard.elite8active.bt.readSoundMode
import io.github.kindleshard.elite8active.findmy.EarbudsAudio
import io.github.kindleshard.elite8active.gnp.Battery
import io.github.kindleshard.elite8active.gnp.SoundMode
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.TimeUnit

/** One battery reading. Percentages are null when that part didn't report. */
data class Sample(val time: Long, val left: Int?, val right: Int?, val case: Int?, val anc: Boolean?) {
    fun encode() = listOf(time, left ?: "", right ?: "", case ?: "", anc?.let { if (it) 1 else 0 } ?: "").joinToString(",")

    companion object {
        fun decode(line: String): Sample? {
            val p = line.split(',')
            if (p.size != 5) return null
            return Sample(p[0].toLongOrNull() ?: return null, p[1].toIntOrNull(), p[2].toIntOrNull(), p[3].toIntOrNull(), p[4].toIntOrNull()?.let { it == 1 })
        }
    }
}

/** How one earbud's battery is doing, from the drops seen while it was out of the case. */
data class SideHealth(val drainPerHour: Double, val hoursObserved: Double, val ancShare: Double) {
    /** What a full charge lasts at the measured rate. */
    val runtimeHours: Double get() = 100.0 / drainPerHour

    /** Jabra's rating for the same mix of ANC and non-ANC listening. */
    val ratedHours: Double get() = ancShare * RATED_ANC_HOURS + (1 - ancShare) * RATED_OFF_HOURS

    val percentOfRated: Int get() = (runtimeHours / ratedHours * 100).coerceAtMost(100.0).toInt()

    val verdict: String
        get() = when {
            percentOfRated >= 80 -> "Good"
            percentOfRated >= 60 -> "Some wear"
            else -> "Worn"
        }

    companion object {
        // Jabra's data sheet for the Elite 8 Active: up to 8 h with ANC on, 14 h with it off.
        const val RATED_ANC_HOURS = 8.0
        const val RATED_OFF_HOURS = 14.0
    }
}

data class HealthReport(val left: SideHealth?, val right: SideHealth?, val samples: Int) {
    /** One earbud draining clearly faster usually means its battery has aged more. */
    val uneven: Boolean
        get() {
            val l = left?.drainPerHour ?: return false
            val r = right?.drainPerHour ?: return false
            return kotlin.math.abs(l - r) / maxOf(l, r) > 0.25
        }

    val summary: String
        get() = when {
            left == null && right == null -> "Collecting data"
            uneven -> "Uneven"
            else -> listOfNotNull(left, right).minBy { it.percentOfRated }.verdict
        }
}

object BatteryHealth {
    /** Gaps longer than this break a discharge run (the earbuds were probably in the case). */
    private const val MAX_GAP_MS = 40 * 60_000L
    private const val MIN_RUN_MS = 20 * 60_000L
    private const val MIN_RUN_DROP = 3
    /** Battery readings move in 5 % steps, so an estimate needs a fair amount of listening. */
    private const val MIN_HOURS = 0.75
    private const val MIN_DROP = 8

    fun analyze(samples: List<Sample>): HealthReport {
        val sorted = samples.sortedBy { it.time }
        return HealthReport(side(sorted) { it.left }, side(sorted) { it.right }, sorted.size)
    }

    private fun side(samples: List<Sample>, level: (Sample) -> Int?): SideHealth? {
        var drop = 0
        var hours = 0.0
        var ancHours = 0.0
        var run = mutableListOf<Sample>()
        fun close() {
            // Measure from the reading just before the first drop to the last drop, so time spent
            // sitting at the same level (e.g. still full after charging) doesn't count as use.
            val first = (0 until run.size - 1).firstOrNull { level(run[it + 1])!! < level(run[it])!! }
            val last = (run.size - 1 downTo 1).firstOrNull { level(run[it])!! < level(run[it - 1])!! }
            if (first != null && last != null && last > first) {
                val used = run.subList(first, last + 1)
                val span = used.last().time - used.first().time
                val runDrop = level(used.first())!! - level(used.last())!!
                if (span >= MIN_RUN_MS && runDrop >= MIN_RUN_DROP) {
                    drop += runDrop
                    hours += span / 3_600_000.0
                    used.zipWithNext().forEach { (a, b) -> if (a.anc == true) ancHours += (b.time - a.time) / 3_600_000.0 }
                }
            }
            run = mutableListOf()
        }
        for (s in samples) {
            val value = level(s) ?: continue
            val last = run.lastOrNull()
            if (last != null && (s.time - last.time > MAX_GAP_MS || value > level(last)!!)) close()
            run += s
        }
        close()
        if (hours < MIN_HOURS || drop < MIN_DROP) return null
        return SideHealth(drop / hours, hours, (ancHours / hours).coerceIn(0.0, 1.0))
    }
}

/** Battery readings, kept in a small file on the phone. */
object BatteryLog {
    private const val FILE = "battery_log.csv"
    private const val KEEP = 4000
    private const val WORK = "battery_health"

    private fun file(context: Context) = File(context.filesDir, FILE)
    private fun prefs(context: Context) = context.getSharedPreferences("battery", Context.MODE_PRIVATE)

    @Synchronized
    fun record(context: Context, battery: Battery, mode: SoundMode?) {
        val f = file(context)
        val sample = Sample(System.currentTimeMillis(), battery.left, battery.right, battery.case, mode?.let { it == SoundMode.ANC })
        val last = load(context).lastOrNull()
        // Skip repeats of the same reading close together; a change is always kept.
        if (last != null && last.left == sample.left && last.right == sample.right && last.case == sample.case &&
            sample.time - last.time < 10 * 60_000L
        ) return
        f.appendText(sample.encode() + "\n")
        val lines = f.readLines()
        if (lines.size > KEEP + 500) f.writeText(lines.takeLast(KEEP).joinToString("\n", postfix = "\n"))
    }

    @Synchronized
    fun load(context: Context): List<Sample> {
        val f = file(context)
        return if (f.exists()) f.readLines().mapNotNull(Sample::decode) else emptyList()
    }

    @Synchronized
    fun clear(context: Context) {
        file(context).delete()
    }

    fun isTracking(context: Context) = prefs(context).getBoolean("tracking", false)

    fun setTracking(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("tracking", on).apply()
        updateChecks(context)
    }

    /**
     * Background checks every 15 minutes, only while the earbuds are connected: for battery
     * health, and for the low-battery alert.
     */
    fun updateChecks(context: Context) {
        val work = WorkManager.getInstance(context)
        if (isTracking(context) || BatteryAlerts.isLowAlertOn(context)) {
            work.enqueueUniquePeriodicWork(
                WORK, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<BatteryWorker>(15, TimeUnit.MINUTES).build(),
            )
        } else {
            work.cancelUniqueWork(WORK)
        }
    }
}

class BatteryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext
        // Only when the earbuds are out and connected; never wake them up or page for them.
        if (EarbudsAudio.output(app, includeCalls = true) == null) return Result.success()
        runCatching {
            withTimeout(20_000) {
                EarbudsLink.use(app) { c -> BatteryReports.report(app, c.readBattery(), c.readSoundMode()) }
            }
        }
        return Result.success()
    }
}
