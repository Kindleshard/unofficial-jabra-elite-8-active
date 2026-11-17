package io.github.kindleshard.elite8active.gnp

/**
 * GN protocol (GNP) framing, as spoken by Jabra Sound+ on RFCOMM channel 29.
 *
 * Layout: dst, src, seq, (type << 6 | total length), cmd, [sub], payload. Total length is 5..62.
 * See jabra-protocol-notes.md for how each field was confirmed.
 */
enum class FrameType { EVENT, READ, WRITE, REPLY }

internal val Byte.u: Int get() = toInt() and 0xFF

class Frame(val raw: ByteArray) {
    init {
        require(raw.size >= 5) { "GNP frame too short: ${raw.size} bytes" }
    }

    val dst: Int get() = raw[0].u
    val src: Int get() = raw[1].u
    val seq: Int get() = raw[2].u
    val type: FrameType get() = FrameType.entries[raw[3].u shr 6]
    val cmd: Int get() = raw[4].u
    val sub: Int? get() = if (raw.size > 5) raw[5].u else null
    val payload: ByteArray get() = if (raw.size > 6) raw.copyOfRange(6, raw.size) else ByteArray(0)

    override fun toString(): String = "%s %02x->%02x seq=%02x cmd=%02x sub=%s %s".format(
        type, src, dst, seq, cmd, sub?.let { "%02x".format(it) } ?: "--", payload.toHex(),
    )
}

object Gnp {
    const val APP = 0x09
    const val BUDS = 0x04

    const val CMD_IDENT = 0x02
    const val CMD_DEVICE = 0x0D
    const val CMD_STATUS = 0x12
    const val CMD_CONFIG = 0x13
    const val CMD_NACK = 0xFE
    const val CMD_ACK = 0xFF

    fun build(
        dst: Int,
        src: Int,
        seq: Int,
        type: FrameType,
        cmd: Int,
        sub: Int? = null,
        payload: ByteArray = ByteArray(0),
    ): ByteArray {
        val header = if (sub != null) 6 else 5
        val length = header + payload.size
        require(length in 5..62) { "GNP frame length $length outside 5..62" }
        val out = ByteArray(length)
        out[0] = dst.toByte()
        out[1] = src.toByte()
        out[2] = seq.toByte()
        out[3] = ((type.ordinal shl 6) or length).toByte()
        out[4] = cmd.toByte()
        if (sub != null) out[5] = sub.toByte()
        payload.copyInto(out, header)
        return out
    }
}

/** Splits an RFCOMM byte stream into frames using the length bits of byte 3. */
class FrameReader {
    private var buffer = ByteArray(0)

    fun feed(bytes: ByteArray, count: Int = bytes.size): List<Frame> {
        buffer += bytes.copyOf(count)
        val frames = mutableListOf<Frame>()
        while (buffer.size >= 4) {
            val length = buffer[3].u and 0x3F
            if (length < 5) {
                // Lost sync; drop what we have and resynchronise on the next read.
                buffer = ByteArray(0)
                break
            }
            if (buffer.size < length) break
            frames += Frame(buffer.copyOf(length))
            buffer = buffer.copyOfRange(length, buffer.size)
        }
        return frames
    }
}

fun ByteArray.toHex(): String = joinToString(" ") { "%02x".format(it.u) }

fun hexBytes(text: String): ByteArray =
    text.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
