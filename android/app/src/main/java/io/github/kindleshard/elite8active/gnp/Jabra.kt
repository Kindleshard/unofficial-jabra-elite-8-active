package io.github.kindleshard.elite8active.gnp

/**
 * Features decoded for the Jabra Elite 8 Active (firmware 4.6.0).
 * Every value here was confirmed against Sound+ and written/read back from the earbuds;
 * see jabra-protocol-notes.md.
 */
object Jabra {
    const val IDENT_NAME = 0x00
    const val IDENT_FIRMWARE = 0x03

    /**
     * IDENT 11: the product ID, little-endian. `47 2e` = 11847, the number Sound+ files this model's
     * settings under (11847_pelican.json). Every setting in this file was decoded on that model only,
     * so the app won't connect to anything else.
     */
    const val IDENT_PRODUCT_ID = 0x11
    const val SUPPORTED_PRODUCT_ID = 11847
    const val SUPPORTED_NAME = "Jabra Elite 8 Active"

    fun productId(payload: ByteArray): Int? = if (payload.size >= 2) payload[0].u or (payload[1].u shl 8) else null

    /** By product ID; by product name only if the earbuds don't report an ID. */
    fun isSupported(productId: Int?, productName: String) =
        productId?.let { it == SUPPORTED_PRODUCT_ID } ?: productName.contains("Elite 8 Active", ignoreCase = true)

    const val STATUS_BATTERY = 0x02

    const val CONFIG_SOUND_MODE = 0xBE
    const val SOUND_MODE_SLOT = 0x01

    /** Sound mode slot 02: how much HearThrough lets in, stored reversed (`00` = the most, `04` = the least). */
    const val HEARTHROUGH_LEVEL_SLOT = 0x02
    const val HEARTHROUGH_STEPS = 5
    fun hearThroughStep(value: Int) = (HEARTHROUGH_STEPS - 1 - value).coerceIn(0, HEARTHROUGH_STEPS - 1)
    fun hearThroughValue(step: Int) = HEARTHROUGH_STEPS - 1 - step.coerceIn(0, HEARTHROUGH_STEPS - 1)

    /**
     * Voice assistant for press and hold: DEVICE 4d with slot 03 (not CONFIG, which is why no
     * settings scan found it). `00` = the phone's own assistant ("Android default" in Sound+),
     * `11` = Google Assistant built in. Decoded from Sound+'s writes with LE Audio off (2026-10-28).
     */
    const val DEVICE_VOICE_ASSISTANT = 0x4D
    const val VOICE_ASSISTANT_SLOT = 0x03
    val VOICE_ASSISTANTS = listOf("Phone's assistant" to 0x00, "Google Assistant built in" to 0x11)

    /** Sidetone: `[mute][level]`, mute `01` = off; the level is signed, −9..+6 in steps of 3 (0 = default). */
    const val CONFIG_SIDETONE = 0x7C
    val SIDETONE_LEVELS = listOf(-9, -6, -3, 0, 3, 6)
    fun sidetonePayload(on: Boolean, level: Int) = byteArrayOf(if (on) 0 else 1, level.toByte())
    fun sidetoneLevel(payload: ByteArray): Int? = payload.getOrNull(1)?.toInt()
    const val CONFIG_EQ = 0x7D
    const val CONFIG_EQ_ENABLED = 0x7E
    const val CONFIG_BUTTONS = 0x9A
    const val CONFIG_NAME = 0x56

    /**
     * DEVICE 4c is both the event subscription (read and write a 32-bit mask) and the events
     * themselves, whose first byte is the type: `04 <sub>` = CONFIG <sub> changed, `07 OO PP` =
     * the earbuds' link ([parseLink]), `09 01 xx` = sound mode changed.
     */
    const val DEVICE_EVENTS = 0x4C
    const val EVENT_SETTING = 0x04
    const val EVENT_LINK = 0x07
    const val EVENT_SOUND_MODE = 0x09

    /**
     * Mask bit n seems to subscribe to event type n: the earbuds offer `00 00 02 96` (types 1, 2, 4,
     * 7 and 9) and Sound+ always includes 4, 7 and 9, the ones this app uses.
     */
    private const val EVENTS_USED = (1 shl EVENT_SETTING) or (1 shl EVENT_LINK) or (1 shl EVENT_SOUND_MODE)

    /**
     * The mask to write back to the earbuds: the current one plus the events the app uses. Every
     * write, even an unchanged one, makes the earbuds send a link event with the current state.
     */
    fun subscription(current: ByteArray): ByteArray {
        if (current.size != 4) return current
        val mask = current.fold(0) { acc, b -> (acc shl 8) or b.u } or EVENTS_USED
        return ByteArray(4) { (mask shr (24 - 8 * it)).toByte() }
    }

    /**
     * Event `07 OO PP`: OO = `01` while the earbuds are linked to each other, PP = the earbud
     * holding the phone link (`01` right, `00` left). Seen in Sound+'s captures: an earbud going
     * into the case closes the link, and the battery report swaps order when PP changes.
     */
    fun parseLink(payload: ByteArray): EarbudLink? {
        if (payload.size < 3 || payload[0].u != EVENT_LINK) return null
        return EarbudLink(linked = payload[1].u == 1, primary = if (payload[2].u == 1) Side.RIGHT else Side.LEFT)
    }

    const val NAME_MAX_LENGTH = 30

    /** Identity strings are length-prefixed. */
    fun lengthPrefixedString(payload: ByteArray): String {
        if (payload.isEmpty()) return ""
        val length = minOf(payload[0].u, payload.size - 1)
        return String(payload, 1, length, Charsets.US_ASCII)
    }

    /** The case's ID in the battery report (IDENT 28 lists the parts as `04 05 15`). */
    private const val CASE_ID = 0x15

    /**
     * STATUS 02: `80 PP 00 00 NN`, then NN entries of `[id][flags][%]`. PP is the earbud that holds
     * the phone link; the entries are the case (`15`) and the other earbud. 0 % = not reported.
     */
    fun parseBattery(payload: ByteArray): BatteryReport {
        require(payload.size >= 5) { "battery payload too short: ${payload.toHex()}" }
        fun level(b: Byte) = b.u.takeIf { it > 0 }
        var case: Int? = null
        var other: Int? = null
        for (i in 0 until payload[4].u) {
            val at = 5 + 3 * i
            if (at + 2 >= payload.size) break
            if (payload[at].u == CASE_ID) case = level(payload[at + 2]) else other = level(payload[at + 2])
        }
        return BatteryReport(primary = level(payload[1]), other = other, case = case)
    }

    /** Fixed per-band (frequency field, Q field); only the gains change. */
    private val EQ_BANDS = listOf(180 to 1730, 750 to 1654, 3000 to 1577, 12000 to 1597, 22800 to 2088)
    val EQ_BAND_LABELS = listOf("Bass", "Low mid", "Mid", "High mid", "Treble")

    /** Per-band gain range Sound+'s custom equalizer writes (probably 0.01 dB units). */
    const val EQ_GAIN_MIN = -450
    const val EQ_GAIN_MAX = 450

    /** Write payload: band count, three zero bytes, then (freq u16, gain s16, Q u16) per band, big-endian. */
    fun eqPayload(gains: List<Int>): ByteArray {
        require(gains.size == EQ_BANDS.size) { "expected ${EQ_BANDS.size} gains" }
        val out = mutableListOf<Byte>(EQ_BANDS.size.toByte(), 0, 0, 0)
        EQ_BANDS.zip(gains).forEach { (band, gain) ->
            out += u16(band.first)
            out += u16(gain.coerceIn(EQ_GAIN_MIN, EQ_GAIN_MAX) and 0xFFFF)
            out += u16(band.second)
        }
        return out.toByteArray()
    }

    /** Read-back payload: 4-byte header, then 7 bytes per band (the last band has no type byte). */
    fun parseEqGains(payload: ByteArray): List<Int> {
        val bands = payload[0].u
        return (0 until bands).map { i ->
            val offset = 4 + 7 * i + 2
            ((payload[offset].u shl 8) or payload[offset + 1].u).toShort().toInt()
        }
    }

    /** CONFIG 56 holds the name as plain ASCII, no length byte. */
    fun namePayload(name: String): ByteArray {
        val clean = name.trim().filter { it.code in 0x20..0x7E }.take(NAME_MAX_LENGTH)
        require(clean.isNotEmpty()) { "name must contain printable ASCII" }
        return clean.toByteArray(Charsets.US_ASCII)
    }

    private fun u16(value: Int) = listOf((value shr 8).toByte(), value.toByte())
}

enum class SoundMode(val code: Int, val label: String) {
    ANC(0x04, "ANC"),
    HEAR_THROUGH(0x02, "HearThrough"),
    OFF(0x01, "Off");

    companion object {
        fun of(code: Int) = entries.firstOrNull { it.code == code }
    }
}

enum class Side { LEFT, RIGHT }

/** Whether the earbuds are linked to each other, and which one talks to the phone. */
data class EarbudLink(val linked: Boolean, val primary: Side) {
    /** The other earbud is offline (in the case or out of range) while they aren't linked. */
    fun isOnline(side: Side) = linked || side == primary
}

/** The battery report as the earbuds send it: by role, not by side. */
data class BatteryReport(val primary: Int?, val other: Int?, val case: Int?) {
    /**
     * Left and right, given which earbud holds the phone link (the right one, which the phone
     * pairs with, until the earbuds say). An offline earbud keeps reporting its last level, so
     * it's left out.
     */
    fun forSides(link: EarbudLink?): Battery {
        val other = other.takeIf { link?.linked != false }
        return if ((link?.primary ?: Side.RIGHT) == Side.RIGHT) {
            Battery(left = other, right = primary, case = case)
        } else {
            Battery(left = primary, right = other, case = case)
        }
    }
}

/** Percentages, null when that part is offline or didn't report. */
data class Battery(val left: Int?, val right: Int?, val case: Int?)

enum class EqPreset(val label: String, val gains: List<Int>) {
    NEUTRAL("Neutral", listOf(0, 0, 0, 0, 0)),
    BASS_BOOST("Bass boost", listOf(270, -118, 0, 0, 0)),
    SMOOTH("Smooth", listOf(0, 118, 0, -118, 0)),
    SPEECH("Speech", listOf(-270, 0, 0, 61, -120)),
    TREBLE_BOOST("Treble boost", listOf(-90, -118, 0, 118, 240)),
    ENERGIZE("Energize", listOf(180, 0, -118, -61, 180));

    companion object {
        /** The earbuds store only the curve, so the preset is recognised by its gains. */
        fun matching(gains: List<Int>?) = entries.firstOrNull { it.gains == gains }
    }
}

/**
 * On/off settings. [compare] limits matching to the first N bytes of the value, for settings
 * that carry extra bytes (Spatial Sound). [needsWearing] mirrors Sound+: the earbuds ignore
 * writes to these unless wearing detection is on.
 */
enum class Toggle(
    val label: String,
    val sub: Int,
    val readPayload: ByteArray,
    private val prefix: ByteArray,
    private val off: ByteArray,
    private val on: ByteArray,
    val needsWearing: Boolean = false,
    private val compare: Int = -1,
) {
    SPATIAL_SOUND("Spatial Sound", 0xE4, none, none, hexBytes("040401"), hexBytes("050501"), compare = 2),
    WEARING_DETECTION("Wearing detection", 0x92, none, none, b(0), b(1)),
    AUTO_PAUSE("Auto-pause music", 0x8E, none, b(0), b(0), b(1), needsWearing = true),
    AUTO_ANSWER("Auto-answer calls", 0x91, none, b(1), b(0), b(1), needsWearing = true),
    AUTO_MUTE("Auto-mute microphones", 0x8F, none, b(0), b(0), b(1), needsWearing = true),
    AUTO_HEARTHROUGH("Automatic HearThrough in mono mode", 0xF0, none, none, b(0), b(1), needsWearing = true),
    // Only the first byte is on/off; the second is the sidetone level (see Jabra.sidetonePayload).
    SIDETONE("Sidetone", 0x7C, none, none, b(1, 0), b(0, 0), compare = 1),
    MUTE_REMINDER("Mute reminder", 0x1E, none, none, b(0), b(0x14)),
    AUTO_VOLUME("Automatic call volume", 0xF1, none, none, b(0), b(1)),
    REDUCE_WIND("Reduce wind", 0xEF, b(0), b(0), b(0), b(1));

    fun parse(payload: ByteArray): Boolean? {
        if (payload.size < prefix.size) return null
        val value = payload.copyOfRange(prefix.size, payload.size)
        fun same(other: ByteArray) =
            if (compare > 0) value.size >= compare && value.copyOf(compare).contentEquals(other.copyOf(compare))
            else value.contentEquals(other)
        return when {
            same(on) -> true
            same(off) -> false
            else -> null
        }
    }

    fun writePayload(enabled: Boolean): ByteArray = prefix + if (enabled) on else off

    companion object {
        fun bySub(sub: Int) = entries.firstOrNull { it.sub == sub }
    }
}

/** Multiple-choice settings: each option's value is written and read back as-is. */
enum class Choice(val label: String, val sub: Int, val options: List<Pair<String, ByteArray>>) {
    SOUND_MODE_CYCLE(
        "Mode button cycles", 0xBF,
        listOf("HearThrough/ANC/Off" to hexBytes("03000102"), "HearThrough/Off" to hexBytes("020001"), "HearThrough/ANC" to hexBytes("020102")),
    ),
    HEARTHROUGH_AUDIO("When HearThrough turns on", 0x76, listOf("Keep playing" to b(1), "Mute audio" to b(2))),
    IN_CALL_EQ("In-call audio", 0x15, listOf("Neutral" to b(0), "Bass" to b(1), "Treble" to b(2))),
    SLEEP_MODE(
        "Sleep after", 0x90,
        listOf("15 min" to b(0x03), "30 min" to b(0x06), "1 hour" to b(0x0C), "2 hours" to b(0x18), "Never" to b(0)),
    ),
    GUIDANCE("Headset guidance", 0x3A, listOf("Voice" to b(1), "Tone" to b(0), "None" to b(2)));

    /** Index into [options], or null for a value Sound+ doesn't offer. */
    fun parse(payload: ByteArray): Int? = options.indexOfFirst { it.second.contentEquals(payload) }.takeIf { it >= 0 }

    companion object {
        fun bySub(sub: Int) = entries.firstOrNull { it.sub == sub }
    }
}

private val none = ByteArray(0)
private fun b(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

/** One MyControls assignment: CONFIG 9A record [menu][earbud][gesture][action]. */
data class ButtonAssignment(val menu: Int, val earbud: Int, val gesture: Int, val action: Int) {
    fun writePayload() = byteArrayOf(menu.toByte(), earbud.toByte(), gesture.toByte(), action.toByte())
}

object Buttons {
    /** CONFIG 9b holds the factory layout, in the same format as the live one (9a). */
    const val CONFIG_DEFAULTS = 0x9B

    // Menu 03 isn't in the list the earbuds give apps (CONFIG ad = 00 01 02), so Sound+ hides it.
    val MENUS = mapOf(0 to "Music and media", 1 to "Incoming calls", 2 to "Active calls", 3 to "Dialling (hidden)")
    val EARBUDS = mapOf(0 to "Left", 1 to "Right")
    val GESTURES = mapOf(0 to "Single press", 1 to "Double press", 2 to "Triple press")
    val ACTIONS = mapOf(
        0x00 to "None",
        0x01 to "Play/pause",
        0x02 to "Next track",
        0x03 to "Restart track",
        0x04 to "Spotify Tap",
        0x05 to "HearThrough/ANC/Off",
        0x06 to "Reject call",
        0x07 to "Answer call",
        0x08 to "Answer and hold active call",
        0x09 to "End call",
        0x0A to "Hold active call",
        0x0B to "Mute/unmute microphone",
        0x0C to "Sidetone on/off",
    )

    /** The actions Sound+ offers in each menu (same for every gesture and both earbuds). */
    val OPTIONS = mapOf(
        0 to listOf(0x00, 0x01, 0x02, 0x03, 0x05),
        1 to listOf(0x00, 0x06, 0x07, 0x08, 0x0B, 0x0C),
        2 to listOf(0x00, 0x09, 0x0A, 0x0B, 0x0C),
        // Not offered by Sound+; the calls actions that make sense before the other side answers.
        3 to listOf(0x00, 0x09, 0x0B, 0x0C),
    )

    fun actionName(code: Int) = ACTIONS[code] ?: "Unknown (0x%02x)".format(code)

    fun parse(payload: ByteArray): List<ButtonAssignment> =
        (0..payload.size - 4 step 4).map { i ->
            ButtonAssignment(payload[i].u, payload[i + 1].u, payload[i + 2].u, payload[i + 3].u)
        }
}
