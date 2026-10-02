package dev.camilo.st2mode.widget

import android.annotation.SuppressLint
import android.appwidget.AppWidgetManager
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.work.Operation
import android.content.ComponentName
import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.camilo.st2mode.AppSettings
import dev.camilo.st2mode.SessionOwner
import dev.camilo.st2mode.St2Session
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

object St2WidgetRefresh {
    private const val WORK_NAME = "widget_mode_refresh"

    fun hasWidgets(context: Context): Boolean = AppWidgetManager.getInstance(context)
        .getAppWidgetIds(ComponentName(context, St2ModeWidget::class.java)).isNotEmpty()

    fun sync(context: Context): Operation {
        val manager = WorkManager.getInstance(context)
        if (!AppSettings(context).state.value.widgetRefresh || !hasWidgets(context)) {
            return manager.cancelUniqueWork(WORK_NAME)
        }
        val request = PeriodicWorkRequestBuilder<St2WidgetRefreshWorker>(15, TimeUnit.MINUTES)
            .setInitialDelay(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        return manager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}

/** Read only, bounded, and never scans or interferes with an existing app/widget session. */
class St2WidgetRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.Main.immediate) {
        val context = applicationContext
        if (!AppSettings(context).state.value.widgetRefresh || !St2WidgetRefresh.hasWidgets(context)) {
            return@withContext Result.success()
        }
        val target = St2ModeWidget.targetOrNull(context) ?: return@withContext Result.success()
        if (!audioConnected(context, target.bondedAddress)) return@withContext Result.success()
        val client = St2Session.acquireRefreshOrNull(context) ?: return@withContext Result.success()
        var openedConnection = false
        try {
            withTimeoutOrNull(12_000L) {
                client.refreshBonded()
                client.state.first { it.selectedAddress != null || it.errorMessage != null }
                if (client.state.value.selectedAddress != target.bondedAddress) return@withTimeoutOrNull
                if (!AppSettings(context).state.value.widgetRefresh || !St2Session.canContinueRefresh()) {
                    return@withTimeoutOrNull
                }
                // Do not interrupt an activity's discovery or connection attempt after it pauses.
                val current = client.state.value
                if (current.ready) {
                    client.refreshModeAndAwait()
                    return@withTimeoutOrNull
                }
                if (current.status != "disconnected" && current.status != "error") return@withTimeoutOrNull
                openedConnection = true
                client.connect(allowScan = false)
                client.state.first {
                    (it.ready && it.currentMode != null) || it.status == "error" ||
                        it.status == "select endpoint" || it.selectedAddress != target.bondedAddress
                }
            }
        } catch (_: SecurityException) {
            // Permissions may be revoked after checking the command target.
        } finally {
            if (openedConnection) {
                client.disconnectAfterRefresh { close -> St2Session.runRefreshCleanupIfIdle(client, close) }
            }
            St2Session.release(SessionOwner.WidgetRefresh)
            St2ModeWidget.updateAll(context, preserveStatus = true)
        }
        Result.success()
    }

    @SuppressLint("MissingPermission")
    private suspend fun audioConnected(context: Context, address: String): Boolean =
        withTimeoutOrNull(2_000L) {
            suspendCancellableCoroutine { continuation ->
                val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
                if (adapter == null) {
                    continuation.resume(false)
                    return@suspendCancellableCoroutine
                }
                val listener = object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                        val connected = try {
                            proxy.connectedDevices.any { it.address.equals(address, ignoreCase = true) }
                        } catch (_: SecurityException) {
                            false
                        } finally {
                            adapter.closeProfileProxy(profile, proxy)
                        }
                        if (continuation.isActive) continuation.resume(connected)
                    }
                    override fun onServiceDisconnected(profile: Int) {
                        if (continuation.isActive) continuation.resume(false)
                    }
                }
                val accepted = try {
                    adapter.getProfileProxy(context, listener, BluetoothProfile.A2DP)
                } catch (_: SecurityException) { false }
                if (!accepted && continuation.isActive) continuation.resume(false)
            }
        } ?: false
}

/** Start scheduling for widgets that were added before this feature was installed. */
class St2WidgetScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        St2WidgetRefresh.sync(context).result.addListener(
            { pending.finish() }, ContextCompat.getMainExecutor(context),
        )
    }
}
