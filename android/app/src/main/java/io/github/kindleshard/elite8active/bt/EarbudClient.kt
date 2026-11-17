package io.github.kindleshard.elite8active.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import io.github.kindleshard.elite8active.gnp.Frame
import io.github.kindleshard.elite8active.gnp.FrameReader
import io.github.kindleshard.elite8active.gnp.FrameType
import io.github.kindleshard.elite8active.gnp.Gnp
import io.github.kindleshard.elite8active.gnp.Jabra
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class GnpNackException(val code: Int, message: String) : IOException(message)

/** A Jabra device that answers, but isn't the model this app was made for. */
class UnsupportedModelException(val productName: String) : IOException(
    "This app only works with the ${Jabra.SUPPORTED_NAME}. " +
        (if (productName.isNotBlank()) "\"$productName\" isn't supported." else "These earbuds aren't supported."),
)

/** GNP client over RFCOMM. Replies are matched to requests by sequence number. */
@SuppressLint("MissingPermission")
class EarbudClient(private val device: BluetoothDevice) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<Frame>>()
    private val sendLock = Mutex()
    private var nextSeq = 0
    private var socket: BluetoothSocket? = null
    private var readJob: Job? = null

    private val _events = MutableSharedFlow<Frame>(extraBufferCapacity = 64)
    /** Unsolicited frames from the earbuds (battery updates, setting-changed notifications). */
    val events: SharedFlow<Frame> = _events

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    /**
     * The earbuds expose two serial-port records (Airoha_APP on 21, "SPP Dev" on 29); only 29
     * speaks GNP. Try the standard SPP UUID first, then channel 29 directly, and keep whichever
     * answers a GNP identity read. Then check the model: throws [UnsupportedModelException] for
     * anything but the one this app's settings were decoded on.
     */
    suspend fun connect() = withContext(Dispatchers.IO) {
        val attempts: List<Pair<String, () -> BluetoothSocket>> = listOf(
            "SPP UUID" to { device.createRfcommSocketToServiceRecord(SPP_UUID) },
            "channel $GNP_CHANNEL" to {
                device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    .invoke(device, GNP_CHANNEL) as BluetoothSocket
            },
        )
        val failures = mutableListOf<String>()
        for ((label, open) in attempts) {
            val candidate = try {
                open().also { it.connect() }
            } catch (e: Exception) {
                failures += "$label: ${e.cause?.message ?: e.message}"
                continue
            }
            attach(candidate)
            val name = try {
                Jabra.lengthPrefixedString(request(FrameType.READ, Gnp.CMD_IDENT, Jabra.IDENT_NAME, timeoutMs = 2000).payload)
            } catch (e: IOException) {
                failures += "$label: no GNP reply (${e.message})"
                detach()
                continue
            }
            val product = try {
                Jabra.productId(request(FrameType.READ, Gnp.CMD_IDENT, Jabra.IDENT_PRODUCT_ID, timeoutMs = 2000).payload)
            } catch (e: IOException) {
                null
            }
            if (!Jabra.isSupported(product, name)) {
                detach()
                throw UnsupportedModelException(name)
            }
            return@withContext
        }
        throw IOException("Couldn't open the earbuds' control channel (" + failures.joinToString("; ") + ")")
    }

    private fun attach(s: BluetoothSocket) {
        socket = s
        _connected.value = true
        val reader = FrameReader()
        readJob = scope.launch {
            val buffer = ByteArray(1024)
            try {
                val input = s.inputStream
                while (isActive) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    reader.feed(buffer, n).forEach(::dispatch)
                }
            } catch (_: IOException) {
                // Socket closed or link lost.
            } finally {
                _connected.value = false
                pending.values.forEach { it.completeExceptionally(IOException("Connection closed")) }
                pending.clear()
            }
        }
    }

    private fun dispatch(frame: Frame) {
        if (frame.type == FrameType.REPLY) {
            pending.remove(frame.seq)?.complete(frame)
        } else {
            _events.tryEmit(frame)
        }
    }

    private fun detach() {
        readJob?.cancel()
        runCatching { socket?.close() }
        socket = null
        _connected.value = false
    }

    suspend fun request(
        type: FrameType,
        cmd: Int,
        sub: Int,
        payload: ByteArray = ByteArray(0),
        timeoutMs: Long = 3000,
    ): Frame {
        val s = socket ?: throw IOException("Not connected")
        val reply = CompletableDeferred<Frame>()
        val seq = sendLock.withLock {
            // Events use seq 0, so requests cycle through 1..255.
            nextSeq = nextSeq % 255 + 1
            pending[nextSeq] = reply
            withContext(Dispatchers.IO) {
                s.outputStream.write(Gnp.build(Gnp.BUDS, Gnp.APP, nextSeq, type, cmd, sub, payload))
                s.outputStream.flush()
            }
            nextSeq
        }
        val frame = try {
            withTimeoutOrNull(timeoutMs) { reply.await() }
        } finally {
            pending.remove(seq)
        } ?: throw IOException("The earbuds didn't answer")
        if (frame.cmd == Gnp.CMD_NACK) {
            val code = frame.sub ?: 0
            val reason = NACK_REASONS[code] ?: "error 0x%02x".format(code)
            throw GnpNackException(code, "The earbuds refused the change ($reason)")
        }
        return frame
    }

    suspend fun read(cmd: Int, sub: Int, payload: ByteArray = ByteArray(0)): ByteArray =
        request(FrameType.READ, cmd, sub, payload).payload

    suspend fun write(cmd: Int, sub: Int, payload: ByteArray) {
        val reply = request(FrameType.WRITE, cmd, sub, payload)
        if (reply.cmd != Gnp.CMD_ACK) throw IOException("Expected ACK, got $reply")
    }

    override fun close() {
        detach()
        scope.cancel()
    }

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        const val GNP_CHANNEL = 29

        private val NACK_REASONS = mapOf(
            0xF3 to "bad state", 0xF5 to "illegal address", 0xF6 to "unknown command",
            0xF8 to "internal error", 0xFA to "illegal value", 0xFB to "out of sequence",
            0xFD to "access denied", 0xFF to "unknown setting",
        )
    }
}
