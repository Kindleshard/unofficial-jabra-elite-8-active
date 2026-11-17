package io.github.kindleshard.elite8active.findmy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LeftBehindTest {
    @Test
    fun needsRealDistance() {
        val drop = Sighting("", 51.5, -0.12, 20f, 0)
        // About 110 m north: within what two 20 m fixes allow for.
        assertFalse(LeftBehind.movedAway(drop, Sighting("", 51.501, -0.12, 20f, 0)))
        // About 330 m north.
        assertTrue(LeftBehind.movedAway(drop, Sighting("", 51.503, -0.12, 20f, 0)))
        // The same distance with a poor fix doesn't count.
        assertFalse(LeftBehind.movedAway(drop, Sighting("", 51.503, -0.12, 300f, 0)))
    }
}
