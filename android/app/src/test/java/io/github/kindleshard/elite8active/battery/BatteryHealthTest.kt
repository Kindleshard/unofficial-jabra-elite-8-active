package io.github.kindleshard.elite8active.battery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryHealthTest {
    private val minute = 60_000L

    /** A reading every 15 minutes, both earbuds dropping by the given steps per reading. */
    private fun listening(start: Int, leftStep: Int, rightStep: Int, readings: Int, anc: Boolean, from: Long = 0) =
        (0 until readings).map { i -> Sample(from + i * 15 * minute, start - i * leftStep, start - i * rightStep, 90, anc) }

    @Test fun sampleRoundTrips() {
        val s = Sample(123, 85, null, 90, true)
        assertEquals(s, Sample.decode(s.encode()))
    }

    @Test fun healthyEarbudsMatchTheRating() {
        // 3 % per 15 minutes = 12 %/h, so a charge lasts ~8.3 h with ANC on: at the 8 h rating.
        val report = BatteryHealth.analyze(listening(100, 3, 3, 9, anc = true))
        assertEquals(12.0, report.left!!.drainPerHour, 0.01)
        assertEquals(100, report.left!!.percentOfRated)
        assertEquals("Good", report.summary)
    }

    @Test fun fastDrainShowsWear() {
        // 5 % per 15 minutes with ANC off = 5 h per charge against a 14 h rating.
        val report = BatteryHealth.analyze(listening(100, 5, 5, 9, anc = false))
        assertEquals(35, report.right!!.percentOfRated)
        assertEquals("Worn", report.summary)
    }

    @Test fun oneEarbudDrainingFasterIsUneven() {
        val report = BatteryHealth.analyze(listening(100, 2, 4, 9, anc = true))
        assertTrue(report.uneven)
        assertEquals("Uneven", report.summary)
    }

    @Test fun chargingAndGapsAreNotCountedAsUse() {
        // 30 minutes of use, a 2-hour gap (case), charging back up, then 30 more minutes.
        val first = listening(80, 5, 5, 3, anc = true)
        val charged = Sample(3 * 60 * minute, 100, 100, 70, null)
        val second = listening(100, 5, 5, 3, anc = true, from = 3 * 60 * minute + 15 * minute)
        val report = BatteryHealth.analyze(first + charged + second)
        // Each run is 30 min, 10 % down: 1 h and 20 % in total, so 20 %/h.
        assertEquals(20.0, report.left!!.drainPerHour, 0.01)
    }

    @Test fun tooLittleDataGivesNoVerdict() {
        val report = BatteryHealth.analyze(listening(100, 5, 5, 2, anc = true))
        assertNull(report.left)
        assertEquals("Collecting data", report.summary)
    }
}
