package io.github.kindleshard.elite8active.bt

import io.github.kindleshard.elite8active.gnp.Battery
import io.github.kindleshard.elite8active.gnp.BatteryReport
import io.github.kindleshard.elite8active.gnp.EarbudLink
import io.github.kindleshard.elite8active.gnp.Gnp
import io.github.kindleshard.elite8active.gnp.Jabra
import io.github.kindleshard.elite8active.gnp.SoundMode
import io.github.kindleshard.elite8active.gnp.u
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

// Reads shared by the screen, the Quick Settings tile and the battery check.

/**
 * Which earbud holds the phone link and whether the other one is linked to it. The earbuds only
 * send this as an event, but they send it whenever the event subscription is written, so this
 * writes the subscription back (as Sound+ does on every start) and waits for the event.
 * Null if they don't answer.
 */
suspend fun EarbudClient.readLink(): EarbudLink? = try {
    coroutineScope {
        // Listen before writing, so the event can't slip past.
        val event = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeoutOrNull(1_500) {
                events.mapNotNull { if (it.cmd == Gnp.CMD_DEVICE && it.sub == Jabra.DEVICE_EVENTS) Jabra.parseLink(it.payload) else null }.first()
            }
        }
        val current = read(Gnp.CMD_DEVICE, Jabra.DEVICE_EVENTS)
        write(Gnp.CMD_DEVICE, Jabra.DEVICE_EVENTS, Jabra.subscription(current))
        event.await()
    }
} catch (_: IOException) {
    null
}

suspend fun EarbudClient.readBatteryReport(): BatteryReport = Jabra.parseBattery(read(Gnp.CMD_STATUS, Jabra.STATUS_BATTERY))

/** Left, right and case, after asking which earbud is which (see [BatteryReport.forSides]). */
suspend fun EarbudClient.readBattery(): Battery {
    val link = readLink()
    return readBatteryReport().forSides(link)
}

suspend fun EarbudClient.readSoundMode(): SoundMode? =
    SoundMode.of(read(Gnp.CMD_CONFIG, Jabra.CONFIG_SOUND_MODE, byteArrayOf(Jabra.SOUND_MODE_SLOT.toByte()))[0].u)

suspend fun EarbudClient.writeSoundMode(mode: SoundMode) =
    write(Gnp.CMD_CONFIG, Jabra.CONFIG_SOUND_MODE, byteArrayOf(Jabra.SOUND_MODE_SLOT.toByte(), mode.code.toByte()))
