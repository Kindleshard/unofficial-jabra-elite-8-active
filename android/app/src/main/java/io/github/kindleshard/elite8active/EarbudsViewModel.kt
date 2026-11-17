package io.github.kindleshard.elite8active

import android.annotation.SuppressLint
import android.app.Application
import android.content.pm.PackageManager
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.kindleshard.elite8active.battery.BatteryReports
import io.github.kindleshard.elite8active.bt.EarbudClient
import io.github.kindleshard.elite8active.bt.EarbudsLink
import io.github.kindleshard.elite8active.bt.UnsupportedModelException
import io.github.kindleshard.elite8active.bt.readBatteryReport
import io.github.kindleshard.elite8active.bt.readLink
import io.github.kindleshard.elite8active.bt.readSoundMode
import io.github.kindleshard.elite8active.bt.writeSoundMode
import io.github.kindleshard.elite8active.firmware.FirmwareCheck
import io.github.kindleshard.elite8active.gnp.Battery
import io.github.kindleshard.elite8active.gnp.BatteryReport
import io.github.kindleshard.elite8active.gnp.ButtonAssignment
import io.github.kindleshard.elite8active.gnp.Buttons
import io.github.kindleshard.elite8active.gnp.Choice
import io.github.kindleshard.elite8active.gnp.EarbudLink
import io.github.kindleshard.elite8active.gnp.EqPreset
import io.github.kindleshard.elite8active.gnp.Frame
import io.github.kindleshard.elite8active.gnp.Gnp
import io.github.kindleshard.elite8active.gnp.Jabra
import io.github.kindleshard.elite8active.gnp.Side
import io.github.kindleshard.elite8active.gnp.SoundMode
import io.github.kindleshard.elite8active.gnp.Toggle
import io.github.kindleshard.elite8active.gnp.u
import io.github.kindleshard.elite8active.store.StateCache
import io.github.kindleshard.elite8active.tile.TileState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

enum class ConnectionStatus { Idle, Connecting, Connected, Reconnecting, Failed }

data class EarbudsState(
    val status: ConnectionStatus = ConnectionStatus.Idle,
    val error: String? = null,
    /** Why the last connection attempt failed. */
    val connectFailure: String? = null,
    /** Sound+ can hold the control channel; if it's installed, offer a way to stop it. */
    val soundPlusInstalled: Boolean = false,
    val pending: Int = 0,
    val refreshing: Boolean = false,
    val name: String? = null,
    val firmware: String? = null,
    /** Which earbud talks to the phone and whether the other is linked; null until they say. */
    val link: EarbudLink? = null,
    val batteryReport: BatteryReport? = null,
    val mode: SoundMode? = null,
    /** 0 (lets in the least) .. 4 (the most). */
    val hearThroughLevel: Int? = null,
    /** Signed, one of Jabra.SIDETONE_LEVELS. */
    val sidetoneLevel: Int? = null,
    /** Raw code from DEVICE 4d 03, see Jabra.VOICE_ASSISTANTS. */
    val voiceAssistant: Int? = null,
    val eqGains: List<Int>? = null,
    val toggles: Map<Toggle, Boolean?> = emptyMap(),
    val choices: Map<Choice, Int?> = emptyMap(),
    val buttons: Map<Int, List<ButtonAssignment>> = emptyMap(),
) {
    val connected: Boolean get() = status == ConnectionStatus.Connected
    /** Something has been read from the earbuds, so the screens have values to show. */
    val hasData: Boolean get() = name != null
    val battery: Battery? get() = batteryReport?.forSides(link)
    /** Known to be in the case or out of range. Only meaningful while connected. */
    fun isOffline(side: Side) = connected && link?.isOnline(side) == false
    val eqPreset: EqPreset? get() = EqPreset.matching(eqGains)
    val wearingDetection: Boolean get() = toggles[Toggle.WEARING_DETECTION] == true
    val busy: Boolean get() = pending > 0
}

/** What the screens can ask for. */
interface EarbudsActions {
    fun connect()
    /** Reads everything again, or reconnects if there's no connection. */
    fun refresh()
    fun setMode(mode: SoundMode)
    fun setHearThroughLevel(step: Int)
    fun setSidetoneLevel(level: Int)
    fun setEq(preset: EqPreset)
    fun setEqGains(gains: List<Int>)
    fun setToggle(toggle: Toggle, enabled: Boolean)
    fun setChoice(choice: Choice, index: Int)
    fun setButton(assignment: ButtonAssignment)
    fun resetButtons()
    fun setVoiceAssistant(code: Int)
    fun rename(name: String)
    fun dismissError()
}

@SuppressLint("MissingPermission")
class EarbudsViewModel(application: Application) : AndroidViewModel(application), EarbudsActions {
    // Start from what was last read, so the tabs show at once and fill in as the app connects.
    private val _state = MutableStateFlow(
        (StateCache.load(application) ?: EarbudsState()).copy(soundPlusInstalled = isInstalled(SOUND_PLUS)),
    )
    val state: StateFlow<EarbudsState> = _state
    private var client: EarbudClient? = null
    private var connectJob: Job? = null
    private var releaseJob: Job? = null
    private val sessionJobs = mutableListOf<Job>()

    init {
        viewModelScope.launch {
            _state.map { it.forCache() }.distinctUntilChanged().drop(1).collectLatest {
                delay(1_000)
                withContext(Dispatchers.IO) { StateCache.save(getApplication(), it) }
            }
        }
    }

    /** Only what came from the earbuds; the connection's state is never cached. */
    private fun EarbudsState.forCache() = EarbudsState(
        name = name, firmware = firmware, link = link, batteryReport = batteryReport, mode = mode,
        hearThroughLevel = hearThroughLevel, sidetoneLevel = sidetoneLevel, voiceAssistant = voiceAssistant,
        eqGains = eqGains, toggles = toggles, choices = choices, buttons = buttons,
    )

    /** Called when the screen becomes visible: keep the connection, or open one. */
    fun onForeground() {
        releaseJob?.cancel()
        if (client == null) connect()
    }

    /**
     * Called when the screen is hidden. Release the control channel so Sound+ and the tile can
     * have it, but not straight away: a quick look at another app or the screen going off for a
     * moment shouldn't mean reconnecting.
     */
    fun onBackground() {
        releaseJob?.cancel()
        releaseJob = viewModelScope.launch {
            delay(RELEASE_AFTER_MS)
            connectJob?.cancel()
            disconnect()
            _state.update { it.copy(status = ConnectionStatus.Idle) }
        }
    }

    override fun connect() {
        if (connectJob?.isActive == true) return
        connectJob = viewModelScope.launch {
            _state.update { it.copy(status = ConnectionStatus.Connecting, connectFailure = null) }
            val failure = tryConnect() ?: return@launch
            _state.update {
                it.copy(status = ConnectionStatus.Failed, connectFailure = failure, soundPlusInstalled = isInstalled(SOUND_PLUS))
            }
        }
    }

    /** After the link drops (earbuds back in the case), keep retrying quietly for a while. */
    private fun reconnectAfterDrop() {
        if (connectJob?.isActive == true) return
        connectJob = viewModelScope.launch {
            _state.update { it.copy(status = ConnectionStatus.Reconnecting) }
            var wait = 3_000L
            val deadline = System.currentTimeMillis() + RECONNECT_WINDOW_MS
            while (System.currentTimeMillis() < deadline) {
                delay(wait)
                if (tryConnect() == null) return@launch
                wait = (wait * 2).coerceAtMost(15_000L)
            }
            _state.update { it.copy(status = ConnectionStatus.Failed, connectFailure = "The earbuds are in the case or out of reach.") }
        }
    }

    /** Returns null on success, or a reason. */
    private suspend fun tryConnect(): String? = EarbudsLink.connectLock.withLock {
        val candidates = try {
            EarbudsLink.candidates(getApplication())
        } catch (e: IOException) {
            return@withLock e.message
        }
        if (candidates.isEmpty()) return@withLock "No paired Jabra earbuds found."
        disconnect()
        var unsupported: UnsupportedModelException? = null
        for (device in candidates) {
            val attempt = EarbudClient(device)
            try {
                attempt.connect()
            } catch (e: Exception) {
                attempt.close()
                if (e is CancellationException) throw e
                if (e is UnsupportedModelException) unsupported = e
                Log.w(TAG, "connect via ${device.address.takeLast(5)} failed: ${e.message}")
                continue
            }
            EarbudsLink.remember(getApplication(), device)
            startSession(attempt)
            return@withLock null
        }
        unsupported?.message ?: "The earbuds' control channel is busy or out of reach."
    }

    private fun startSession(c: EarbudClient) {
        client = c
        EarbudsLink.active = c
        _state.update { it.copy(status = ConnectionStatus.Connected, connectFailure = null) }
        sessionJobs += viewModelScope.launch { c.events.collect(::onEvent) }
        sessionJobs += viewModelScope.launch {
            c.connected.first { !it }
            if (client === c) {
                disconnect()
                reconnectAfterDrop()
            }
        }
        readAll()
    }

    private fun disconnect() {
        sessionJobs.forEach { it.cancel() }
        sessionJobs.clear()
        if (EarbudsLink.active === client) EarbudsLink.active = null
        client?.close()
        client = null
    }

    override fun refresh() {
        if (client != null) {
            readAll()
        } else {
            // Don't wait for the next retry when asked directly.
            if (_state.value.status == ConnectionStatus.Reconnecting) connectJob?.cancel()
            connect()
        }
    }

    private fun readAll() = run(refresh = true) { c ->
        readName(c)
        val firmware = Jabra.lengthPrefixedString(c.read(Gnp.CMD_IDENT, Jabra.IDENT_FIRMWARE))
        _state.update { it.copy(firmware = firmware) }
        FirmwareCheck.rememberInstalled(getApplication(), firmware)
        // The link first, so the battery report can be told apart into left and right.
        c.readLink()?.let { link -> _state.update { it.copy(link = link) } }
        readBattery(c)
        readMode(c)
        readHearThroughLevel(c)
        readEq(c)
        Toggle.entries.forEach { readToggle(c, it) }
        Choice.entries.forEach { readChoice(c, it) }
        readAllButtons(c)
        readVoiceAssistant(c)
    }

    override fun setMode(mode: SoundMode) = change({ it.copy(mode = mode) }, { readMode(it) }) { it.writeSoundMode(mode) }

    override fun setEq(preset: EqPreset) = setEqGains(preset.gains)

    /** Same sequence as Sound+: enable EQ, write the curve, and disable EQ for the flat curve. */
    override fun setEqGains(gains: List<Int>) = change({ it.copy(eqGains = gains) }, { readEq(it) }) { c ->
        c.write(Gnp.CMD_CONFIG, Jabra.CONFIG_EQ_ENABLED, byteArrayOf(1))
        c.write(Gnp.CMD_CONFIG, Jabra.CONFIG_EQ, Jabra.eqPayload(gains))
        if (gains.all { it == 0 }) c.write(Gnp.CMD_CONFIG, Jabra.CONFIG_EQ_ENABLED, byteArrayOf(0))
    }

    override fun setHearThroughLevel(step: Int) = change({ it.copy(hearThroughLevel = step) }, { readHearThroughLevel(it) }) { c ->
        c.write(Gnp.CMD_CONFIG, Jabra.CONFIG_SOUND_MODE, byteArrayOf(Jabra.HEARTHROUGH_LEVEL_SLOT.toByte(), Jabra.hearThroughValue(step).toByte()))
    }

    override fun setVoiceAssistant(code: Int) = change({ it.copy(voiceAssistant = code) }, { readVoiceAssistant(it) }) { c ->
        c.write(Gnp.CMD_DEVICE, Jabra.DEVICE_VOICE_ASSISTANT, byteArrayOf(Jabra.VOICE_ASSISTANT_SLOT.toByte(), code.toByte()))
    }

    override fun setSidetoneLevel(level: Int) = change({ it.copy(sidetoneLevel = level) }, { readToggle(it, Toggle.SIDETONE) }) { c ->
        val on = _state.value.toggles[Toggle.SIDETONE] == true
        c.write(Gnp.CMD_CONFIG, Jabra.CONFIG_SIDETONE, Jabra.sidetonePayload(on, level))
    }

    override fun setToggle(toggle: Toggle, enabled: Boolean) = change(
        { it.copy(toggles = it.toggles + (toggle to enabled)) },
        { c ->
            readToggle(c, toggle)
            // The earbuds keep or drop the dependent settings themselves; re-read them.
            if (toggle == Toggle.WEARING_DETECTION) Toggle.entries.filter { it.needsWearing }.forEach { readToggle(c, it) }
        },
    ) { c ->
        // Sidetone's on/off shares its value with the level, so keep the level as it is.
        val payload = if (toggle == Toggle.SIDETONE) Jabra.sidetonePayload(enabled, _state.value.sidetoneLevel ?: 0) else toggle.writePayload(enabled)
        c.write(Gnp.CMD_CONFIG, toggle.sub, payload)
    }

    override fun setChoice(choice: Choice, index: Int) = change({ it.copy(choices = it.choices + (choice to index)) }, { readChoice(it, choice) }) { c ->
        c.write(Gnp.CMD_CONFIG, choice.sub, choice.options[index].second)
    }

    override fun setButton(assignment: ButtonAssignment) = change(
        { s ->
            val menu = s.buttons[assignment.menu].orEmpty().map {
                if (it.earbud == assignment.earbud && it.gesture == assignment.gesture) assignment else it
            }
            s.copy(buttons = s.buttons + (assignment.menu to menu))
        },
        { readButtons(it, assignment.menu) },
    ) { c ->
        c.write(Gnp.CMD_CONFIG, Jabra.CONFIG_BUTTONS, assignment.writePayload())
    }

    /** Copies the factory layout (CONFIG 9b) over the live one, one changed gesture at a time. */
    override fun resetButtons() = run(onError = { readAllButtons(it) }) { c ->
        for (menu in Buttons.MENUS.keys) {
            val defaults = Buttons.parse(c.read(Gnp.CMD_CONFIG, Buttons.CONFIG_DEFAULTS, byteArrayOf(menu.toByte())))
            val current = Buttons.parse(c.read(Gnp.CMD_CONFIG, Jabra.CONFIG_BUTTONS, byteArrayOf(menu.toByte())))
            defaults.filter { it !in current }.forEach { c.write(Gnp.CMD_CONFIG, Jabra.CONFIG_BUTTONS, it.writePayload()) }
            readButtons(c, menu)
        }
    }

    override fun rename(name: String) = run { c ->
        c.write(Gnp.CMD_CONFIG, Jabra.CONFIG_NAME, Jabra.namePayload(name))
        readName(c)
    }

    override fun dismissError() = _state.update { it.copy(error = null) }

    private suspend fun readName(c: EarbudClient) {
        val name = String(c.read(Gnp.CMD_CONFIG, Jabra.CONFIG_NAME), Charsets.US_ASCII)
        _state.update { it.copy(name = name) }
    }

    private suspend fun readBattery(c: EarbudClient) {
        val report = c.readBatteryReport()
        _state.update { it.copy(batteryReport = report) }
        publishBattery()
    }

    /** Passes the battery on to the tile, the battery-health log and the low-battery alert. */
    private fun publishBattery() {
        val s = _state.value
        val battery = s.battery ?: return
        viewModelScope.launch(Dispatchers.IO) { BatteryReports.report(getApplication(), battery, s.mode) }
    }

    private suspend fun readMode(c: EarbudClient) {
        val mode = c.readSoundMode()
        _state.update { it.copy(mode = mode) }
        TileState.save(getApplication(), mode = mode)
    }

    private suspend fun readHearThroughLevel(c: EarbudClient) {
        val value = c.read(Gnp.CMD_CONFIG, Jabra.CONFIG_SOUND_MODE, byteArrayOf(Jabra.HEARTHROUGH_LEVEL_SLOT.toByte()))[0].u
        _state.update { it.copy(hearThroughLevel = Jabra.hearThroughStep(value)) }
    }

    private suspend fun readVoiceAssistant(c: EarbudClient) {
        val code = c.read(Gnp.CMD_DEVICE, Jabra.DEVICE_VOICE_ASSISTANT, byteArrayOf(Jabra.VOICE_ASSISTANT_SLOT.toByte()))[0].u
        _state.update { it.copy(voiceAssistant = code) }
    }

    private suspend fun readEq(c: EarbudClient) {
        val gains = Jabra.parseEqGains(c.read(Gnp.CMD_CONFIG, Jabra.CONFIG_EQ, byteArrayOf(0)))
        _state.update { it.copy(eqGains = gains) }
    }

    private suspend fun readToggle(c: EarbudClient, toggle: Toggle) {
        val payload = c.read(Gnp.CMD_CONFIG, toggle.sub, toggle.readPayload)
        val value = toggle.parse(payload)
        _state.update {
            it.copy(
                toggles = it.toggles + (toggle to value),
                sidetoneLevel = if (toggle == Toggle.SIDETONE) Jabra.sidetoneLevel(payload) else it.sidetoneLevel,
            )
        }
    }

    private suspend fun readChoice(c: EarbudClient, choice: Choice) {
        val index = choice.parse(c.read(Gnp.CMD_CONFIG, choice.sub))
        _state.update { it.copy(choices = it.choices + (choice to index)) }
    }

    private suspend fun readButtons(c: EarbudClient, menu: Int) {
        val map = Buttons.parse(c.read(Gnp.CMD_CONFIG, Jabra.CONFIG_BUTTONS, byteArrayOf(menu.toByte())))
        _state.update { it.copy(buttons = it.buttons + (menu to map)) }
    }

    private suspend fun readAllButtons(c: EarbudClient) = Buttons.MENUS.keys.forEach { readButtons(c, it) }

    /** Re-reads a CONFIG setting the earbuds say has changed. */
    private suspend fun readSetting(c: EarbudClient, sub: Int) {
        when (sub) {
            Jabra.CONFIG_SOUND_MODE -> {
                readMode(c)
                readHearThroughLevel(c)
            }
            Jabra.CONFIG_EQ -> readEq(c)
            Jabra.CONFIG_BUTTONS -> readAllButtons(c)
            Jabra.CONFIG_NAME -> readName(c)
            else -> {
                Toggle.bySub(sub)?.let { readToggle(c, it) }
                Choice.bySub(sub)?.let { readChoice(c, it) }
            }
        }
    }

    /** Keeps the screen in sync with changes made on the earbuds (buttons, case, other apps). */
    private fun onEvent(frame: Frame) {
        val c = client ?: return
        val payload = frame.payload
        fun reread(block: suspend (EarbudClient) -> Unit) {
            viewModelScope.launch { runCatching { block(c) } }
        }
        when {
            frame.cmd == Gnp.CMD_STATUS && frame.sub == Jabra.STATUS_BATTERY -> {
                val report = runCatching { Jabra.parseBattery(payload) }.getOrNull() ?: return
                _state.update { it.copy(batteryReport = report) }
                publishBattery()
            }
            frame.cmd == Gnp.CMD_DEVICE && frame.sub == Jabra.DEVICE_EVENTS && payload.isNotEmpty() -> when (payload[0].u) {
                Jabra.EVENT_LINK -> Jabra.parseLink(payload)?.let { link ->
                    _state.update { it.copy(link = link) }
                    publishBattery()
                }
                Jabra.EVENT_SOUND_MODE -> reread { readMode(it) }
                Jabra.EVENT_SETTING -> payload.getOrNull(1)?.u?.let { sub -> reread { readSetting(it, sub) } }
            }
        }
    }

    /**
     * Shows [optimistic] at once, writes, then reads the real value back, which also undoes the
     * change if the earbuds refused it.
     */
    private fun change(
        optimistic: (EarbudsState) -> EarbudsState,
        readBack: suspend (EarbudClient) -> Unit,
        write: suspend (EarbudClient) -> Unit,
    ) = run(onError = readBack) { c ->
        _state.update(optimistic)
        write(c)
        readBack(c)
    }

    private fun run(
        refresh: Boolean = false,
        onError: suspend (EarbudClient) -> Unit = {},
        block: suspend (EarbudClient) -> Unit,
    ) {
        viewModelScope.launch {
            _state.update { it.copy(pending = it.pending + 1, refreshing = it.refreshing || refresh) }
            try {
                val c = awaitClient()
                if (c == null) {
                    _state.update { it.copy(error = "The earbuds aren't connected.") }
                    return@launch
                }
                try {
                    block(c)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _state.update { it.copy(error = e.message ?: "Something went wrong talking to the earbuds.") }
                    runCatching { onError(c) }
                }
            } finally {
                _state.update { it.copy(pending = it.pending - 1, refreshing = if (refresh) false else it.refreshing) }
            }
        }
    }

    /** The connection; a change made while it's being opened (e.g. just after coming back) waits for it. */
    private suspend fun awaitClient(): EarbudClient? {
        if (client == null && _state.value.status == ConnectionStatus.Connecting) connectJob?.join()
        return client
    }

    private fun isInstalled(pkg: String) = try {
        getApplication<Application>().packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    override fun onCleared() {
        disconnect()
    }

    companion object {
        const val SOUND_PLUS = "com.jabra.moments"
        private const val TAG = "Elite8Active"
        private const val RECONNECT_WINDOW_MS = 2 * 60_000L
        private const val RELEASE_AFTER_MS = 15_000L
    }
}
