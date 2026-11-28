package dev.camilo.st2mode.widget

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import dev.camilo.st2mode.MainActivity
import dev.camilo.st2mode.SessionOwner
import dev.camilo.st2mode.St2Session
import dev.camilo.st2mode.decodeWidgetMode
import dev.camilo.st2mode.ble.AncMode
import dev.camilo.st2mode.ble.St2GattClient
import dev.camilo.st2mode.ble.Transport
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
 * Commands go through the shared [St2GattClient], so a ready app session is reused
 * and no second socket competes for the earbuds' single SPP channel. A cold command
 * is registered before connecting and written first: over RFCOMM when the earbuds
 * are connected for audio, otherwise over the saved BLE endpoint.
 */
class St2WidgetCommandService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val requests = Channel<AncMode>(Channel.CONFLATED)
    private var commandJob: Job? = null
    private var client: St2GattClient? = null
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
                    commandJob = null
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelfResult(lastStartId)
                }
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun sendMode(mode: AncMode) {
        St2ModeWidget.updateAll(this, "Changing to ${mode.label}…", replaceCaption = true)
        startForeground(NOTIFICATION_ID, notification(mode), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        val started = SystemClock.elapsedRealtime()
        var shared: St2GattClient? = null
        var bondedAddress: String? = null
        val sent = try {
            withTimeoutOrNull(COMMAND_TIMEOUT_MS) {
                val target = St2ModeWidget.targetOrNull(this@St2WidgetCommandService)
                    ?: return@withTimeoutOrNull false
                val session = client ?: St2Session.acquire(this@St2WidgetCommandService, SessionOwner.WidgetCommand)
                    .also { client = it }
                session.refreshBonded()
                // Never replace an activity's selection or start endpoint discovery from the widget.
                if (session.state.value.selectedAddress != target.bondedAddress) return@withTimeoutOrNull false
                shared = session
                bondedAddress = target.bondedAddress
                session.connectAndSetModeAndAwait(mode, target.bondedAddress)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalStateException) {
            false
        }
        val outcome = when (sent) {
            // RFCOMM marks its transport before writing. BLE writes a queued SET
            // before notification setup marks the session ready, so null is BLE.
            true -> (shared?.state?.value?.transport ?: Transport.Ble).name
            false -> "Failed"
            null -> "Timeout"
        }
        debugLog("widget-$outcome-${mode.label} elapsedMs=${SystemClock.elapsedRealtime() - started}")
        St2ModeWidget.updateAll(this, when (sent) {
            true -> null
            null -> "Timed out. Open app to check"
            false -> "Couldn't change mode. Open app"
        })
        val session = shared ?: return
        val address = bondedAddress ?: return
        if (sent != true) return
        // The mode is already shown. A cold BLE session becomes ready after its
        // subscriptions; battery failures never turn this into an error.
        withTimeoutOrNull(BATTERY_TIMEOUT_MS) {
            val state = session.state.first {
                it.ready || it.status == "error" || it.status == "disconnected" || it.selectedAddress != address
            }
            if (state.ready && state.selectedAddress == address) session.refreshBatteryAndAwait()
        }
    }

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
        private const val BATTERY_TIMEOUT_MS = 8_000L
    }
}
