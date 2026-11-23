package dev.camilo.st2mode.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.SystemClock
import java.io.IOException
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * GAIA over RFCOMM, framed for Classic Bluetooth.
 *
 * Space Travel 2 serves GAIA on the standard SPP UUID while audio holds the
 * Classic link, so this skips BLE connection and GATT discovery. Verified on
 * 2026-11-22 (Xiaomi 21081111RG): socket ~250–530 ms, GET reply 64–176 ms.
 * Battery replies arrive in order after a SET, so the post-command battery read
 * also shows the SET reached the earbuds.
 *
 * Frame: [FF][version][flags][payload length][vendor 2][command 2][payload][checksum?]
 * The body after the length byte(s) is identical to [Gaia.frame]. The earbuds
 * reply with version 4; we send version 1 without flags, as the official app does.
 */
internal object GaiaClassic {
    val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")

    private const val SOF = 0xFF.toByte()
    private const val VERSION = 0x01.toByte()
    private const val FLAG_CHECKSUM = 1
    private const val FLAG_LENGTH_EXTENSION = 2
    private const val MAX_PACKET = 4 + 2 + 4 + 0xFFFF + 1

    /** Wraps a BLE-style GAIA frame (vendor, command, payload) for RFCOMM. */
    fun frame(ble: ByteArray): ByteArray {
        val payloadLength = ble.size - 4
        require(payloadLength in 0..0xFF) { "payload too long for a short GAIA header" }
        return byteArrayOf(SOF, VERSION, 0, payloadLength.toByte()) + ble
    }

    /** Reassembles packets from a byte stream that may split or join frames. */
    class Reader {
        private var buffer = ByteArray(0)

        /** Returns BLE-style frames, so [Gaia.parse] and the existing decoders apply. */
        fun feed(bytes: ByteArray): List<ByteArray> {
            buffer += bytes
            val out = mutableListOf<ByteArray>()
            var i = 0
            while (i < buffer.size) {
                if (buffer[i] != SOF) { i++; continue }
                if (buffer.size - i < 4) break
                val version = buffer[i + 1].toInt() and 0xFF
                val flags = buffer[i + 2].toInt() and 0xFF
                val extended = version >= 4 && flags and FLAG_LENGTH_EXTENSION != 0
                val lengthBytes = if (extended) 2 else 1
                if (buffer.size - i < 3 + lengthBytes) break
                val length = if (extended) {
                    ((buffer[i + 3].toInt() and 0xFF) shl 8) or (buffer[i + 4].toInt() and 0xFF)
                } else {
                    buffer[i + 3].toInt() and 0xFF
                }
                val bodyStart = i + 3 + lengthBytes
                val total = 3 + lengthBytes + 4 + length + if (flags and FLAG_CHECKSUM != 0) 1 else 0
                if (total > MAX_PACKET) { i++; continue }
                if (buffer.size - i < total) break
                out += buffer.copyOfRange(bodyStart, bodyStart + 4 + length)
                i += total
            }
            buffer = buffer.copyOfRange(i, buffer.size)
            return out
        }
    }
}

/**
 * One RFCOMM GAIA session. Callers issue one request at a time; requests are
 * also serialized internally. Cancelling any call closes the socket.
 */
internal class GaiaRfcommLink private constructor(
    private val socket: BluetoothSocket,
    private val trace: (String) -> Unit,
) {
    private val packets = LinkedBlockingQueue<ByteArray>()
    private val requests = Mutex()
    @Volatile private var closed = false

    private val reader = Thread({
        val parser = GaiaClassic.Reader()
        val buf = ByteArray(1024)
        try {
            val input = socket.inputStream
            while (!closed) {
                val n = input.read(buf)
                if (n < 0) break
                parser.feed(buf.copyOf(n)).forEach(packets::put)
            }
        } catch (_: IOException) {
        } finally {
            closed = true
            packets.put(CLOSED)
        }
    }, "st2-rfcomm-rx").apply { isDaemon = true; start() }

    val isOpen: Boolean get() = !closed

    /**
     * Writes one SET. The firmware sends no SET reply, and GET keeps reporting the
     * previous mode for over 1.5 s while the transition plays, so a completed write
     * is the success signal, as with the BLE write callback. Throws [IOException]
     * when nothing could be written, which is the only case safe to retry elsewhere.
     */
    suspend fun sendMode(mode: AncMode) = requests.withLock {
        write(Gaia.setMode(mode.setCode))
        trace("rfcomm-set-written ${mode.label}")
    }

    suspend fun readBattery(timeoutMs: Long = REPLY_TIMEOUT_MS): BatteryLevels? = requests.withLock {
        val reply = request(Gaia.getBatteryLevels(), Gaia.GET_BATTERY_LEVELS_RESPONSE, timeoutMs)
        reply?.let { decodeBatteryReport(it, System.currentTimeMillis()) }
    }

    fun close() {
        closed = true
        try {
            socket.close()
        } catch (_: IOException) {
        }
    }

    /** Waits for [response] or its GAIA error; other packets are unsolicited and dropped. */
    private suspend fun request(frame: ByteArray, response: Int, timeoutMs: Long): ByteArray? {
        packets.removeIf { it !== CLOSED }
        write(frame)
        val error = response or ERROR_TYPE_BITS
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            val left = deadline - SystemClock.elapsedRealtime()
            if (left <= 0) return null
            val packet = runInterruptible(Dispatchers.IO) { packets.poll(left, TimeUnit.MILLISECONDS) }
                ?: return null
            if (packet === CLOSED) {
                packets.put(CLOSED)
                return null
            }
            val command = Gaia.parse(packet)?.commandValue ?: continue
            if (command == response) return packet
            if (command == error) return null
        }
    }

    private suspend fun write(ble: ByteArray) {
        if (closed) throw IOException("RFCOMM closed")
        val bytes = GaiaClassic.frame(ble)
        cancellableSocketCall(socket) {
            socket.outputStream.write(bytes)
            socket.outputStream.flush()
        }
    }

    companion object {
        private const val REPLY_TIMEOUT_MS = 1_500L
        private const val ERROR_TYPE_BITS = 1 shl 7
        private val CLOSED = ByteArray(0)

        /** Opens an SPP socket on the existing Classic link, or returns null. */
        @SuppressLint("MissingPermission")
        suspend fun open(device: BluetoothDevice, trace: (String) -> Unit = {}): GaiaRfcommLink? {
            val socket = try {
                device.createRfcommSocketToServiceRecord(GaiaClassic.SPP_UUID)
            } catch (_: IOException) {
                return null
            }
            return try {
                cancellableSocketCall(socket) { socket.connect() }
                GaiaRfcommLink(socket, trace).also { trace("rfcomm-connected") }
            } catch (_: IOException) {
                try { socket.close() } catch (_: IOException) {}
                null
            }
        }

        /** Socket calls ignore interrupts; closing the socket is what unblocks them. */
        private suspend fun <T> cancellableSocketCall(socket: BluetoothSocket, block: () -> T): T =
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation {
                    try { socket.close() } catch (_: IOException) {}
                }
                val thread = Thread({
                    val result = runCatching(block)
                    result.fold(
                        onSuccess = { if (continuation.isActive) continuation.resume(it) },
                        onFailure = { if (continuation.isActive) continuation.resumeWithException(it) },
                    )
                }, "st2-rfcomm-io")
                thread.isDaemon = true
                thread.start()
            }
    }
}
