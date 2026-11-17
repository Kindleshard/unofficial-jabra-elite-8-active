package io.github.kindleshard.elite8active.findmy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FindMyTest {
    private fun at(address: String, lat: Double, lng: Double, time: Long) = Sighting(address, lat, lng, 12f, time)

    @Test fun sightingRoundTrips() {
        val s = at("00:11:22:33:44:AB", 51.5007, -0.1246, 1_700_000_000_000)
        assertEquals(s, Sighting.decode(s.address, s.encode()))
        assertNull(Sighting.decode("x", "garbage"))
    }

    @Test fun distanceIsInMetres() {
        // 0.001° of latitude is about 111 m.
        val d = at("a", 51.5, -0.12, 0).distanceTo(at("b", 51.501, -0.12, 0))
        assertEquals(111.2, d, 0.5)
    }

    @Test fun bothEarbudsDisconnectingTogetherIsOnePlace() {
        val state = FindMyState(true, listOf(at("left", 51.5, -0.12, 1_000), at("right", 51.5002, -0.12, 2_000)))
        assertEquals(listOf("right"), state.places.map { it.address })
    }

    @Test fun anEarbudLostElsewhereIsItsOwnPlaceNewestFirst() {
        val state = FindMyState(true, listOf(at("left", 51.5, -0.12, 1_000), at("right", 51.55, -0.12, 2_000)))
        assertEquals(listOf("right", "left"), state.places.map { it.address })
    }
}
