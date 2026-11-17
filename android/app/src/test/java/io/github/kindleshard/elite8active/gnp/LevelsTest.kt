package io.github.kindleshard.elite8active.gnp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** Values read from the earbuds while moving Sound+'s sliders (2026-10-27). */
class LevelsTest {
    @Test fun hearThroughLevelIsStoredReversed() {
        // Slider step 4 (hear the most) read 00, step 0 read 04.
        assertEquals(4, Jabra.hearThroughStep(0x00))
        assertEquals(0, Jabra.hearThroughStep(0x04))
        assertEquals(0x01, Jabra.hearThroughValue(3))
    }

    @Test fun sidetoneLevelIsSigned() {
        // Step 0 read `00 f7`, step 5 read `00 06`.
        assertEquals(-9, Jabra.sidetoneLevel(hexBytes("00f7")))
        assertEquals(6, Jabra.sidetoneLevel(hexBytes("0006")))
        assertArrayEquals(hexBytes("01fd"), Jabra.sidetonePayload(on = false, level = -3))
    }

    @Test fun sidetoneSwitchIgnoresTheLevel() {
        assertEquals(true, Toggle.SIDETONE.parse(hexBytes("00fa")))
        assertEquals(false, Toggle.SIDETONE.parse(hexBytes("0103")))
    }
}
