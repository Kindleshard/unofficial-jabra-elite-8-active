package io.github.kindleshard.elite8active.hearing

import org.junit.Assert.assertEquals
import org.junit.Test

class HearingProfileTest {
    // Thresholds shaped exactly like typical hearing (relative to the 3 kHz tone at -60 dBFS).
    private val typical = listOf(-44.0, -52.0, -60.0, -42.0, -44.0)

    @Test fun typicalHearingGivesAFlatCurve() =
        assertEquals(listOf(0, 0, 0, 0, 0), HearingProfile.eqGains(typical, typical))

    @Test fun weakerTrebleIsBoostedByHalfTheDifference() {
        // Needing the 12 kHz tone 6 dB louder than typical gives +3.0 on Treble.
        val weak = typical.toMutableList().also { it[4] += 6.0 }
        assertEquals(listOf(0, 0, 0, 0, 300), HearingProfile.eqGains(weak, weak))
    }

    @Test fun boostsStopAtTheEarbudsLimit() {
        val veryWeak = typical.toMutableList().also { it[0] += 20.0 }
        assertEquals(450, HearingProfile.eqGains(veryWeak, veryWeak)[0])
    }
}
