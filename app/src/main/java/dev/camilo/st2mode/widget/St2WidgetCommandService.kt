package dev.camilo.st2mode.widget

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import dev.camilo.st2mode.MainActivity
import dev.camilo.st2mode.SessionOwner
import dev.camilo.st2mode.St2SelectionStore
import dev.camilo.st2mode.St2Session
import dev.camilo.st2mode.decodeWidgetMode
import dev.camilo.st2mode.ble.AncMode
import dev.camilo.st2mode.ble.GaiaRfcommLink
import dev.camilo.st2mode.ble.St2GattClient
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Short, user-triggered commands only. The activity remains the sole polling owner.
 *
 * A ready BLE session is used as is. Otherwise, when the earbuds are connected for
 * audio, the command goes over RFCOMM on that Classic link, which skips BLE
 * connection and GATT discovery. Any RFCOMM failure falls back to the BLE path.
 */
class St2WidgetCommandService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val requests = Channel<AncMode>(Channel.CONFLATED)
    private var commandJob: Job? = null
    private var client: St2GattClient? = null
    private var rfcomm: GaiaRfcommLink? = null
    private var rfcommAddress: String? = null
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        val mode = if (intent?.action == ACTION_SET_MODE) {
            decodeWidgetMode(intent.getStringExtra(EXTRA_MODE))
        } else null
        try {
            // Honor the startup contract even if Bluetooth permission vanished after the tap.
            val startupType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            }
            startForeground(NOTIFICATION_ID, notification(mode), startupType)
            if (mode == null || St2ModeWidget.targetOrNull(this) == null) {
                St2ModeWidget.updateAll(this, "Open app to set up Bluetooth")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelfResult(startId)
                return START_NOT_STICKY
            }
            startForeground(NOTIFICATION_ID, notification(mode), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (_: SecurityException) {
            St2ModeWidget.updateAll(this, "Open the app to allow Bluetooth")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(startId)
            return START_NOT_STICKY
        } catch (_: IllegalStateException) {
            St2ModeWidget.updateAll(this, "Open the app to change mode")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        requests.trySend(mode)
        if (commandJob?.isActive != true) {
            commandJob = scope.launch {
                try {
                    while (true) {
                        val next = requests.tryReceive().getOrNull() ?: break
                        sendMode(next)
                    }
                } finally {
                    closeRfcomm()
                    commandJob = null
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelfResult(lastStartId)
                }
            }
        }
        return START_NOT_STICKY
    }

    private enum class Outcome { Rfcomm, Ble, Failed }

    private suspend fun sendMode(mode: AncMode) {
        St2ModeWidget.updateAll(this, "Changing to ${mode.label}…", replaceCaption = true)
        startForeground(NOTIFICATION_ID, notification(mode), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        val started = SystemClock.elapsedRealtime()
        val result = try {
            withTimeoutOrNull(COMMAND_TIMEOUT_MS) {
                val target = St2ModeWidget.targetOrNull(this@St2WidgetCommandService)
                    ?: return@withTimeoutOrNull Outcome.Failed
                if (sendOverRfcomm(mode, target.bondedAddress)) return@withTimeoutOrNull Outcome.Rfcomm
                if (sendOverBle(mode, target.bondedAddress)) Outcome.Ble else Outcome.Failed
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            Outcome.Failed
        } catch (_: IllegalStateException) {
            Outcome.Failed
        }
        logLatency("widget-${result?.name ?: "Timeout"}-${mode.label}", started)
        St2ModeWidget.updateAll(this, when (result) {
            Outcome.Rfcomm, Outcome.Ble -> null
            null -> "Timed out. Open app to check"
            Outcome.Failed -> "Couldn't change mode. Open app"
        })
        // The mode is already shown; battery failures never turn this into an error.
        if (result == Outcome.Rfcomm) withTimeoutOrNull(BATTERY_TIMEOUT_MS) { refreshBatteryOverRfcomm() }
    }

    /** False means "use BLE": a ready session exists, audio is down, or nothing was written. */
    private suspend fun sendOverRfcomm(mode: AncMode, bondedAddress: String): Boolean {
        val shared = St2Session.clientOrNull()?.state?.value
        if (shared != null && shared.ready && shared.selectedAddress == bondedAddress) {
            debugLog("rfcomm-skip ble-ready")
            return false
        }
        val link = rfcommLinkOrNull(bondedAddress) ?: return false
        try {
            link.sendMode(mode)
        } catch (_: IOException) {
            // Nothing reached the earbuds, so the BLE fallback cannot apply it twice.
            debugLog("rfcomm-fallback write-failed")
            closeRfcomm()
            return false
        }
        // Only record the mode for the device that is still selected.
        val store = St2SelectionStore(this)
        if (store.loadSelectedAddress().equals(bondedAddress, ignoreCase = true)) {
            store.saveLastKnownMode(mode)
            St2Session.clientOrNull()?.recordConfirmedMode(mode)
        }
        return true
    }

    private suspend fun rfcommLinkOrNull(bondedAddress: String): GaiaRfcommLink? {
        rfcomm?.let { if (it.isOpen && rfcommAddress == bondedAddress) return it }
        closeRfcomm()
        debugLog("rfcomm-audio-check")
        if (!isAudioConnected(this, bondedAddress)) {
            debugLog("rfcomm-skip audio-disconnected")
            return null
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: return null
        val link = GaiaRfcommLink.open(adapter.getRemoteDevice(bondedAddress), ::debugLog) ?: run {
            debugLog("rfcomm-fallback connect-failed")
            return null
        }
        rfcomm = link
        rfcommAddress = bondedAddress
        return link
    }

    private suspend fun refreshBatteryOverRfcomm() {
        val link = rfcomm ?: return
        val address = rfcommAddress ?: return
        val levels = try {
            link.readBattery()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return
        val store = St2SelectionStore(this)
        if (!store.loadSelectedAddress().equals(address, ignoreCase = true)) return
        store.saveBatteryLevels(address, levels)
        St2ModeWidget.updateAll(this, preserveStatus = true)
    }

    private fun closeRfcomm() {
        rfcomm?.close()
        rfcomm = null
        rfcommAddress = null
    }

    private suspend fun sendOverBle(mode: AncMode, bondedAddress: String): Boolean {
        val shared = client ?: St2Session.acquire(this, SessionOwner.WidgetCommand).also { client = it }
        shared.refreshBonded()
        // Never replace an activity's selection or start endpoint discovery from the widget.
        if (shared.state.value.selectedAddress != bondedAddress) return false
        if (!shared.connectAndSetModeAndAwait(mode, bondedAddress)) return false
        val state = shared.state.first {
            it.ready || it.status == "error" || it.status == "select endpoint" ||
                it.status == "disconnected" ||
                it.selectedAddress != bondedAddress
        }
        if (!state.ready || state.selectedAddress != bondedAddress) return false
        shared.refreshBatteryAndAwait()
        return true
    }

    private fun logLatency(stage: String, startedMs: Long) =
        debugLog("$stage elapsedMs=${SystemClock.elapsedRealtime() - startedMs}")

    private fun debugLog(message: String) {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            Log.d("st2-latency", "$message t=${SystemClock.elapsedRealtime()}")
        }
    }

    private fun notification(mode: AncMode?): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL_ID, "Mode changes", NotificationManager.IMPORTANCE_LOW,
        ))
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentTitle(getString(dev.camilo.st2mode.R.string.app_name))
            .setContentText(mode?.let { "Changing to ${it.label}" } ?: "Preparing mode change")
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        closeRfcomm()
        requests.close()
        if (client != null) {
            St2Session.release(SessionOwner.WidgetCommand)
            client = null
        }
        super.onDestroy()
    }

    companion object {
        const val ACTION_SET_MODE = "dev.camilo.st2mode.widget.SET_MODE"
        const val EXTRA_MODE = "mode"
        private const val CHANNEL_ID = "widget_mode_commands"
        private const val NOTIFICATION_ID = 1
        private const val COMMAND_TIMEOUT_MS = 30_000L
        private const val BATTERY_TIMEOUT_MS = 2_000L
    }
}
