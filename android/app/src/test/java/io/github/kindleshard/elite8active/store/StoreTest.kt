package io.github.kindleshard.elite8active.store

import io.github.kindleshard.elite8active.EarbudsState
import io.github.kindleshard.elite8active.gnp.BatteryReport
import io.github.kindleshard.elite8active.gnp.ButtonAssignment
import io.github.kindleshard.elite8active.gnp.Choice
import io.github.kindleshard.elite8active.gnp.EarbudLink
import io.github.kindleshard.elite8active.gnp.EqPreset
import io.github.kindleshard.elite8active.gnp.Side
import io.github.kindleshard.elite8active.gnp.SoundMode
import io.github.kindleshard.elite8active.gnp.Toggle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StoreTest {
    @Test
    fun cachesEverythingReadFromTheEarbuds() {
        val state = EarbudsState(
            name = "Jabra Elite 8 Active",
            firmware = "4.6.0",
            link = EarbudLink(linked = false, primary = Side.LEFT),
            batteryReport = BatteryReport(primary = 75, other = null, case = 90),
            mode = SoundMode.HEAR_THROUGH,
            hearThroughLevel = 3,
            sidetoneLevel = -3,
            voiceAssistant = 0x11,
            eqGains = EqPreset.ENERGIZE.gains,
            toggles = mapOf(Toggle.WEARING_DETECTION to true, Toggle.SPATIAL_SOUND to false),
            choices = mapOf(Choice.SLEEP_MODE to 1),
            buttons = mapOf(0 to listOf(ButtonAssignment(0, 0, 1, 4), ButtonAssignment(0, 1, 1, 2)), 2 to listOf(ButtonAssignment(2, 0, 0, 9))),
        )
        assertEquals(state, StateCache.decode(StateCache.encode(state)))
    }

    @Test
    fun aCacheWithoutANameIsIgnored() {
        assertNull(StateCache.decode(StateCache.encode(EarbudsState())))
    }

    @Test
    fun savedEqsRoundTrip() {
        val items = listOf(SavedEq("Podcasts", listOf(-270, 0, 118, 61, -120)), SavedEq("Gym", EqPreset.BASS_BOOST.gains))
        assertEquals(items, EqLibrary.decode(EqLibrary.encode(items)))
        assertEquals(emptyList<SavedEq>(), EqLibrary.decode("not json"))
    }
}
