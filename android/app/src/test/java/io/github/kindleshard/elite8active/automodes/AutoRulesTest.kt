package io.github.kindleshard.elite8active.automodes

import io.github.kindleshard.elite8active.gnp.SoundMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoRulesTest {
    private val gym = Place("gym", "Gym", 51.5, -0.12, SoundMode.ANC)
    private val home = Place("home", "Home", 51.6, -0.1, null)
    private val rules = AutoRules(enabled = true, places = listOf(gym, home))

    @Test
    fun followsWhatYouAreDoing() {
        assertEquals(SoundMode.HEAR_THROUGH to "Walking", rules.pick(emptySet(), Motion.WALKING))
        assertEquals(SoundMode.ANC to "In a vehicle", rules.pick(emptySet(), Motion.VEHICLE))
    }

    @Test
    fun aPlaceWinsOverWhatYouAreDoing() {
        assertEquals(SoundMode.ANC to "At Gym", rules.pick(setOf("gym"), Motion.RUNNING))
    }

    @Test
    fun leaveAsItIsMeansNoChange() {
        // Sitting still is "leave as it is" by default, and so is a place without a mode.
        assertNull(rules.pick(emptySet(), Motion.STILL))
        assertNull(rules.pick(emptySet(), null))
        assertEquals(SoundMode.HEAR_THROUGH to "Walking", rules.pick(setOf("home"), Motion.WALKING))
    }
}
