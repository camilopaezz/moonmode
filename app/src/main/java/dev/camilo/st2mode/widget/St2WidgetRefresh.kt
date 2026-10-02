package dev.camilo.st2mode.widget

import android.annotation.SuppressLint
import android.appwidget.AppWidgetManager
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.ComponentName
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.camilo.st2mode.St2Session
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** One deferrable schedule for every widget; KEEP does not reset the next run. */
object St2WidgetRefresh {
    private const val WORK_NAME = "st2_widget_refresh"

    fun hasWidgets(context: Context): Boolean =
        AppWidgetManager.getInstance(context).getAppWidgetIds(
            ComponentName(context, St2ModeWidget::class.java),
        ).isNotEmpty()

    fun sync(context: Context): Operation {
        val manager = WorkManager.getInstance(context)
        if (!hasWidgets(context)) {
            return manager.cancelUniqueWork(WORK_NAME)
        }
        val request = PeriodicWorkRequestBuilder<St2WidgetRefreshWorker>(15, TimeUnit.MINUTES)
            .setInitialDelay(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        return manager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}

/** Existing widgets must acquire the new schedule when the app is upgraded. */
class St2WidgetScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        St2WidgetRefresh.sync(context).result.addListener(
            { pending.finish() }, ContextCompat.getMainExecutor(context),
        )
    }
}

/** A short read-only BLE session, never a foreground service or a scan. */
class St2WidgetRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.Main.immediate) {
        val context = applicationContext
        if (!St2WidgetRefresh.hasWidgets(context)) return@withContext Result.success()
        // Redraw cached values even when no read is possible, so expired battery data disappears.
        St2ModeWidget.updateAll(context, preserveStatus = true)
        val target = St2ModeWidget.targetOrNull(context) ?: return@withContext Result.success()
        if (!audioConnected(context, target.bondedAddress)) return@withContext Result.success()

        val client = St2Session.acquire(context)
        try {
            withTimeoutOrNull(SESSION_TIMEOUT_MS) {
                client.refreshBonded()
                if (client.state.value.selectedAddress != target.bondedAddress) return@withTimeoutOrNull
                // Discovery and endpoint confirmation remain user actions in the app.
                client.connect(allowScan = false)
                val state = client.state.first {
                    it.ready || it.status == "error" || it.status == "disconnected" ||
                        it.status == "select endpoint" || it.selectedAddress != target.bondedAddress
                }
                if (!state.ready || state.selectedAddress != target.bondedAddress) return@withTimeoutOrNull
                client.refreshModeAndAwait()
                client.refreshBatteryAndAwait()
            }
        } catch (_: SecurityException) {
            // Permissions can disappear between the eligibility check and a Bluetooth callback.
        } finally {
            St2Session.release()
            St2ModeWidget.updateAll(context, preserveStatus = true)
        }
        // Offline or unsupported reads wait for the next period, with no retry storm.
        Result.success()
    }

    @SuppressLint("MissingPermission")
    private suspend fun audioConnected(context: Context, address: String): Boolean =
        withTimeoutOrNull(PROFILE_TIMEOUT_MS) {
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
                        // A late callback must still close its proxy after cancellation or timeout.
                        if (continuation.isActive) continuation.resume(connected)
                    }

                    override fun onServiceDisconnected(profile: Int) {
                        if (continuation.isActive) continuation.resume(false)
                    }
                }
                val accepted = try {
                    adapter.getProfileProxy(context, listener, BluetoothProfile.A2DP)
                } catch (_: SecurityException) {
                    false
                }
                if (!accepted && continuation.isActive) continuation.resume(false)
            }
        } ?: false

    companion object {
        private const val SESSION_TIMEOUT_MS = 12_000L
        private const val PROFILE_TIMEOUT_MS = 2_000L
    }
}
