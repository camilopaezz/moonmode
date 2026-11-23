package dev.camilo.st2mode

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import android.util.Log
import dev.camilo.st2mode.ble.AncMode
import dev.camilo.st2mode.ble.GaiaRfcommLink
import dev.camilo.st2mode.widget.St2ModeWidget
import dev.camilo.st2mode.widget.isAudioConnected
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Sends ANC modes over RFCOMM on the Classic link that audio already holds.
 * Shared by the app and the widget so they reuse one socket and never interleave.
 *
 * [send] returning false means nothing reached the earbuds, so the caller may use
 * BLE without applying the mode twice. Callers [release] the socket when done, for
 * example when the widget service finishes or the activity pauses.
 * Main thread only; the lock orders commands from the app and the widget.
 */
internal object AudioLinkModeSender {
    private val lock = Mutex()
    private var link: GaiaRfcommLink? = null
    private var linkAddress: String? = null

    /** Skips RFCOMM when a ready BLE session can already send in ~30 ms. */
    suspend fun send(context: Context, mode: AncMode, bondedAddress: String): Boolean = lock.withLock {
        val app = context.applicationContext
        val shared = St2Session.clientOrNull()?.state?.value
        if (shared != null && shared.ready && shared.selectedAddress == bondedAddress) {
            debugLog(app, "rfcomm-skip ble-ready")
            return@withLock false
        }
        val current = linkOrNull(app, bondedAddress) ?: return@withLock false
        try {
            current.sendMode(mode)
        } catch (_: IOException) {
            debugLog(app, "rfcomm-fallback write-failed")
            close()
            return@withLock false
        }
        // Only record the mode for the device that is still selected.
        val store = St2SelectionStore(app)
        if (store.loadSelectedAddress().equals(bondedAddress, ignoreCase = true)) {
            store.saveLastKnownMode(mode)
            St2Session.clientOrNull()?.recordDirectMode(mode)
            St2ModeWidget.updateAll(app, preserveStatus = true)
        }
        true
    }

    /** Best effort; failures never turn a sent mode into an error. */
    suspend fun refreshBattery(context: Context): Boolean = lock.withLock {
        val app = context.applicationContext
        val current = link?.takeIf { it.isOpen } ?: return@withLock false
        val address = linkAddress ?: return@withLock false
        val levels = try {
            current.readBattery()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return@withLock false
        val store = St2SelectionStore(app)
        if (!store.loadSelectedAddress().equals(address, ignoreCase = true)) return@withLock false
        store.saveBatteryLevels(address, levels)
        St2ModeWidget.updateAll(app, preserveStatus = true)
        true
    }

    /** Closes the socket after any command in progress, so that command is not cut off. */
    suspend fun release() = withContext(NonCancellable) { lock.withLock { close() } }

    private fun close() {
        link?.close()
        link = null
        linkAddress = null
    }

    private suspend fun linkOrNull(context: Context, bondedAddress: String): GaiaRfcommLink? {
        link?.let { if (it.isOpen && linkAddress == bondedAddress) return it }
        close()
        val started = SystemClock.elapsedRealtime()
        if (!isAudioConnected(context, bondedAddress)) {
            debugLog(context, "rfcomm-skip audio-disconnected")
            return null
        }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return null
        val opened = GaiaRfcommLink.open(adapter.getRemoteDevice(bondedAddress)) ?: run {
            debugLog(context, "rfcomm-fallback connect-failed")
            return null
        }
        debugLog(context, "rfcomm-connected elapsedMs=${SystemClock.elapsedRealtime() - started}")
        link = opened
        linkAddress = bondedAddress
        return opened
    }

    private fun debugLog(context: Context, message: String) {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            Log.d("st2-latency", message)
        }
    }
}
