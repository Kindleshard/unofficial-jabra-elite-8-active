package io.github.kindleshard.elite8active.gnp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Expected bytes are real frames from the Sound+ HCI captures (2026-10-27). */
class GnpTest {
    private fun request(seq: Int, type: FrameType, cmd: Int, sub: Int, payload: String = "") =
        Gnp.build(Gnp.BUDS, Gnp.APP, seq, type, cmd, sub, hexBytes(payload))

    @Test
    fun buildsCapturedRequests() {
        assertArrayEquals(hexBytes("040903460203"), request(0x03, FrameType.READ, Gnp.CMD_IDENT, Jabra.IDENT_FIRMWARE))
        assertArrayEquals(hexBytes("04096f4713be01"), request(0x6f, FrameType.READ, Gnp.CMD_CONFIG, 0xBE, "01"))
        assertArrayEquals(hexBytes("04096c8813be0102"), request(0x6c, FrameType.WRITE, Gnp.CMD_CONFIG, 0xBE, "0102"))
        assertArrayEquals(hexBytes("0409af8813ef0001"), request(0xaf, FrameType.WRITE, Gnp.CMD_CONFIG, 0xEF, "0001"))
        assertArrayEquals(hexBytes("0409108a139a00000101"), request(0x10, FrameType.WRITE, Gnp.CMD_CONFIG, 0x9A, "00000101"))
    }

    @Test
    fun buildsCapturedEqWrite() {
        val frame = Gnp.build(
            Gnp.BUDS, Gnp.APP, 0x48, FrameType.WRITE, Gnp.CMD_CONFIG, Jabra.CONFIG_EQ,
            Jabra.eqPayload(EqPreset.BASS_BOOST.gains),
        )
        assertEquals(
            "040948a8137d0500000000b4010e06c202eeff8a06760bb8000006292ee00000063d591000000828",
            frame.toHex().replace(" ", ""),
        )
    }

    @Test
    fun recognisesTheSupportedModel() {
        // IDENT 11 as read from the earbuds: 11847, the ID Sound+ files this model under.
        assertEquals(Jabra.SUPPORTED_PRODUCT_ID, Jabra.productId(hexBytes("472e")))
        assertTrue(Jabra.isSupported(11847, "Jabra Elite 8 Active"))
        // A different product is refused even with a similar name, e.g. a later generation.
        assertFalse(Jabra.isSupported(12345, "Jabra Elite 8 Active"))
        // Without an ID, the product name decides.
        assertTrue(Jabra.isSupported(null, "Jabra Elite 8 Active"))
        assertFalse(Jabra.isSupported(null, "Jabra Elite 10"))
        assertNull(Jabra.productId(hexBytes("47")))
    }

    @Test
    fun parsesReplyHeader() {
        val reply = Frame(hexBytes("090403cc020305342e362e30"))
        assertEquals(FrameType.REPLY, reply.type)
        assertEquals(Gnp.BUDS, reply.src)
        assertEquals(0x03, reply.seq)
        assertEquals("4.6.0", Jabra.lengthPrefixedString(reply.payload))
    }

    @Test
    fun splitsConcatenatedAndPartialFrames() {
        val reader = FrameReader()
        val stream = hexBytes("090403cc020305342e362e30" + "0904ac" + "c6fc00")
        val first = reader.feed(stream.copyOfRange(0, 14))
        assertEquals(1, first.size)
        val rest = reader.feed(stream.copyOfRange(14, stream.size))
        assertEquals(1, rest.size)
        assertEquals(0xFC, rest[0].cmd)
    }

    @Test
    fun parsesBattery() {
        val bothOut = EarbudLink(linked = true, primary = Side.RIGHT)
        val report = Jabra.parseBattery(hexBytes("80500000021500 5a05 0055"))
        assertEquals(BatteryReport(primary = 80, other = 85, case = 90), report)
        assertEquals(Battery(left = 85, right = 80, case = 90), report.forSides(bothOut))
        assertNull(Jabra.parseBattery(hexBytes("805f00000215005a050000")).forSides(bothOut).left)
    }

    @Test
    fun parsesLinkAndMapsBatteryBySide() {
        // capture4: the right earbud came out of the case (100 %) while the left (75 %) held the
        // phone link, then the right took the link over and the report swapped order.
        assertEquals(EarbudLink(linked = true, primary = Side.LEFT), Jabra.parseLink(hexBytes("070100")))
        assertEquals(EarbudLink(linked = true, primary = Side.RIGHT), Jabra.parseLink(hexBytes("070101")))
        val leftHolds = Jabra.parseBattery(hexBytes("804b0000021500 5a050064"))
        val rightHolds = Jabra.parseBattery(hexBytes("80640000021500 5a05004b"))
        assertEquals(Battery(left = 75, right = 100, case = 90), leftHolds.forSides(Jabra.parseLink(hexBytes("070100"))))
        assertEquals(Battery(left = 75, right = 100, case = 90), rightHolds.forSides(Jabra.parseLink(hexBytes("070101"))))

        // listen.log: the left earbud went into the case; its stale level is dropped.
        val leftInCase = Jabra.parseLink(hexBytes("070001"))!!
        assertEquals(false, leftInCase.isOnline(Side.LEFT))
        assertEquals(true, leftInCase.isOnline(Side.RIGHT))
        assertEquals(Battery(left = null, right = 85, case = 90), Jabra.parseBattery(hexBytes("80550000021500 5a050055")).forSides(leftInCase))

        assertNull(Jabra.parseLink(hexBytes("04ef")))
    }

    @Test
    fun writesBackTheEventSubscription() {
        assertArrayEquals(hexBytes("00000296"), Jabra.subscription(hexBytes("00000296")))
        assertArrayEquals(hexBytes("00000296"), Jabra.subscription(hexBytes("00000006")))
        assertArrayEquals(hexBytes("01"), Jabra.subscription(hexBytes("01")))
    }

    @Test
    fun parsesEqReadBackAndMatchesPreset() {
        val payload = hexBytes(
            "0500001e00b4010e06c21802eeff8a0676180bb800000629182ee00000063d28591000000828",
        )
        val gains = Jabra.parseEqGains(payload)
        assertEquals(EqPreset.BASS_BOOST.gains, gains)
        assertEquals(EqPreset.BASS_BOOST, EqPreset.matching(gains))
    }

    @Test
    fun parsesToggles() {
        assertEquals(true, Toggle.REDUCE_WIND.parse(hexBytes("0001")))
        assertEquals(false, Toggle.REDUCE_WIND.parse(hexBytes("0000")))
        assertEquals(false, Toggle.SIDETONE.parse(hexBytes("0100")))
        assertEquals(true, Toggle.SIDETONE.parse(hexBytes("0000")))
        assertArrayEquals(hexBytes("0001"), Toggle.REDUCE_WIND.writePayload(true))
    }

    @Test
    fun parsesNewTogglesAsReadFromTheEarbuds() {
        // Spatial Sound carries a third byte that is not part of the on/off value.
        assertEquals(false, Toggle.SPATIAL_SOUND.parse(hexBytes("040401")))
        assertEquals(false, Toggle.SPATIAL_SOUND.parse(hexBytes("040400")))
        assertEquals(true, Toggle.SPATIAL_SOUND.parse(hexBytes("050501")))
        assertEquals(true, Toggle.AUTO_ANSWER.parse(hexBytes("0101")))
        assertEquals(false, Toggle.AUTO_PAUSE.parse(hexBytes("0000")))
        assertEquals(true, Toggle.MUTE_REMINDER.parse(hexBytes("14")))
        assertArrayEquals(hexBytes("0101"), Toggle.AUTO_ANSWER.writePayload(true))
    }

    @Test
    fun parsesChoices() {
        assertEquals(0, Choice.SOUND_MODE_CYCLE.parse(hexBytes("03000102")))
        assertEquals(2, Choice.SOUND_MODE_CYCLE.parse(hexBytes("020102")))
        assertEquals("30 min", Choice.SLEEP_MODE.options[Choice.SLEEP_MODE.parse(hexBytes("06"))!!].first)
        assertEquals("Treble", Choice.IN_CALL_EQ.options[Choice.IN_CALL_EQ.parse(hexBytes("02"))!!].first)
        assertNull(Choice.GUIDANCE.parse(hexBytes("07")))
    }

    @Test
    fun buildsNameAndClampsCustomEq() {
        assertEquals("Jabra Elite 8 Active", String(Jabra.namePayload("  Jabra Elite 8 Active ")))
        val payload = Jabra.eqPayload(listOf(999, -999, 0, 0, 0))
        val back = Jabra.parseEqGains(hexBytes("0500001e") + payload.copyOfRange(4, payload.size).toList().chunked(6)
            .flatMapIndexed { i, band -> if (i < 4) band + 0x18.toByte() else band }.toByteArray())
        assertEquals(listOf(450, -450, 0, 0, 0), back)
    }

    @Test
    fun parsesButtonMap() {
        val map = Buttons.parse(hexBytes("000000050001000100000104000101020000020000010203"))
        assertEquals(6, map.size)
        assertEquals(ButtonAssignment(menu = 0, earbud = 0, gesture = 0, action = 0x05), map[0])
        assertEquals("Restart track", Buttons.actionName(map[5].action))
    }
}
