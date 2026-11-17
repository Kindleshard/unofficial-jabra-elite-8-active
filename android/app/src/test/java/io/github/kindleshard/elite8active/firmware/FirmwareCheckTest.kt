package io.github.kindleshard.elite8active.firmware

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirmwareCheckTest {
    @Test
    fun findsTheNewestVersionOnTheReleaseNotesPage() {
        // As on jabra.com (2026-10-28), where each version is its own heading.
        val html = """<h3 class="x">4.6.0</h3><p>Security update</p><h3> 4.5.0 </h3><h3>4.4.4</h3><h3>3.1.6</h3><h3>2.1.8</h3>
            <p>Requires Android 15.0.1 or later</p>"""
        assertEquals(listOf("4.6.0", "4.5.0", "4.4.4", "3.1.6", "2.1.8"), FirmwareCheck.versionsIn(html))
        assertEquals("4.6.0", FirmwareCheck.newest(FirmwareCheck.versionsIn(html)))
    }

    @Test
    fun comparesVersionsByNumber() {
        assertTrue(FirmwareCheck.isNewer("4.10.0", "4.6.0"))
        assertTrue(FirmwareCheck.isNewer("5.0", "4.6.0"))
        assertFalse(FirmwareCheck.isNewer("4.6.0", "4.6.0"))
        assertFalse(FirmwareCheck.isNewer("4.5.9", "4.6.0"))
    }
}
