package io.github.kindleshard.elite8active.tile

import org.junit.Assert.assertEquals
import org.junit.Test

class AncTileTest {
    @Test fun batteryShowsBothEarbuds() = assertEquals("L 85% · R 80%", AncTileService.batteryText(85, 80))

    @Test fun batteryLeavesOutAnEarbudWithoutAReading() = assertEquals("R 80%", AncTileService.batteryText(null, 80))
}
