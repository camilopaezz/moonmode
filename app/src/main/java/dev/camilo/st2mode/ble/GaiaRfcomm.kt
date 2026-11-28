package dev.camilo.st2mode.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * GAIA over RFCOMM, framed for Classic Bluetooth.
 *
 * Space Travel 2 serves GAIA on the standard SPP UUID while audio holds the
 * Classic link, so this skips BLE connection and GATT discovery. Verified on
 * Xiaomi 21081111RG: socket ~400–900 ms, GET reply 60–180 ms, and one socket
 * stayed usable through 10 minutes of 2-second polling and long idle gaps.
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
 * One RFCOMM GAIA channel on the existing Classic link.
 *
 * It only moves frames: [write] reports whether the bytes left the phone, and
 * [start] delivers every received frame in order, then [onClosed] once. Request
 * matching, timeouts and polling live in [St2GattClient], shared with BLE.
 *
 * Space Travel 2 serves one SPP connection at a time. A second connection from
 * another app, such as the official Moondrop app, drops this one, and opening
 * this one while another app holds the channel fails within about half a second.
 */
internal class GaiaRfcommLink private constructor(private val socket: BluetoothSocket) {
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "st2-rfcomm-tx").apply { isDaemon = true } }
    private val closedReported = AtomicBoolean(false)
    @Volatile private var closed = false

    /** Starts the reader. Callbacks run on the reader thread. */
    fun start(onPacket: (ByteArray) -> Unit, onClosed: () -> Unit) {
        Thread({
            val parser = GaiaClassic.Reader()
            val buf = ByteArray(1024)
            try {
                val input = socket.inputStream
                while (!closed) {
                    val n = input.read(buf)
                    if (n < 0) break
                    parser.feed(buf.copyOf(n)).forEach(onPacket)
                }
            } catch (_: IOException) {
            } finally {
                closed = true
                if (closedReported.compareAndSet(false, true)) onClosed()
            }
        }, "st2-rfcomm-rx").apply { isDaemon = true; start() }
    }

    /**
     * Writes one BLE-style frame in order. [done] gets true once the bytes are
     * written. RFCOMM output is unbuffered, so this matches a GATT write callback:
     * delivery, not that the earbuds applied a SET. False means the write did not
     * complete. A SET names an absolute mode, so if part of it did go out, repeating
     * it on another transport sets the same mode again.
     */
    fun write(ble: ByteArray, done: (Boolean) -> Unit) {
        val bytes = GaiaClassic.frame(ble)
        try {
            writer.execute {
                val ok = !closed && runCatching { socket.outputStream.write(bytes) }.isSuccess
                done(ok)
            }
        } catch (_: RejectedExecutionException) {
            done(false)
        }
    }

    /** Closing unblocks a pending read or write. [start]'s onClosed still runs once. */
    fun close() {
        closed = true
        writer.shutdown()
        try {
            socket.close()
        } catch (_: IOException) {
        }
    }

    companion object {
        /** Opens an SPP socket on the existing Classic link, or returns null. */
        @SuppressLint("MissingPermission")
        suspend fun open(device: BluetoothDevice): GaiaRfcommLink? {
            val socket = try {
                device.createRfcommSocketToServiceRecord(GaiaClassic.SPP_UUID)
            } catch (_: IOException) {
                return null
            } catch (_: SecurityException) {
                return null
            }
            return try {
                cancellableSocketCall(socket) { socket.connect() }
                GaiaRfcommLink(socket)
            } catch (_: IOException) {
                try { socket.close() } catch (_: IOException) {}
                null
            } catch (_: SecurityException) {
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
                }, "st2-rfcomm-connect")
                thread.isDaemon = true
                thread.start()
            }
    }
}
