package dev.camilo.st2mode.widget

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import dev.camilo.st2mode.MainActivity
import dev.camilo.st2mode.R
import dev.camilo.st2mode.St2SelectionStore
import dev.camilo.st2mode.St2WidgetTarget
import dev.camilo.st2mode.ble.AncMode
import dev.camilo.st2mode.decodeWidgetMode
import dev.camilo.st2mode.widgetModes
import dev.camilo.st2mode.widgetTargetOrNull

/**
 * Home-screen RemoteViews widget: Off / ANC / Transparency.
 *
 * The footer identifies the selected paired earbuds, while the filled segment
 * remains the last confirmed mode. [updateAll] may add a process-scoped
 * in-flight/failure status. No GATT and no polling live here.
 *
 * Receiver registration (`exported="false"`) is owned by the manifest.
 */
class St2ModeWidget : AppWidgetProvider() {
    override fun onEnabled(context: Context) {
        St2WidgetRefresh.sync(context)
    }

    override fun onDisabled(context: Context) {
        St2WidgetRefresh.sync(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_SET_MODE -> {
                val mode = decodeWidgetMode(intent.getStringExtra(EXTRA_MODE)) ?: return
                // A launcher may retain an old command PendingIntent after Bluetooth changes.
                if (targetOrNull(context) == null) {
                    updateAll(context, "Open app to set up Bluetooth")
                    return
                }
                try {
                    ContextCompat.startForegroundService(context, commandIntent(context, mode))
                } catch (_: SecurityException) {
                    updateAll(context, "Open app to allow Bluetooth")
                } catch (_: IllegalStateException) {
                    updateAll(context, "Open app to change mode")
                }
            }
            else -> super.onReceive(context, intent)
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        St2WidgetRefresh.sync(context)
        appWidgetManager.updateAppWidget(appWidgetIds, buildRemoteViews(context, transientStatus))
    }

    companion object {
        const val ACTION_SET_MODE = "dev.camilo.st2mode.widget.SET_MODE"
        const val EXTRA_MODE = "mode"

        @Volatile
        private var transientStatus: String? = null

        /**
         * Refresh every instance. [status] is the in-flight/failure overlay kept
         * only in this process; pass null to clear it. Last-known mode is always
         * read from [St2SelectionStore], not from [status].
         */
        fun updateAll(context: Context, status: String? = null, preserveStatus: Boolean = false) {
            if (!preserveStatus) transientStatus = status
            val app = context.applicationContext
            val manager = AppWidgetManager.getInstance(app)
            val ids = manager.getAppWidgetIds(ComponentName(app, St2ModeWidget::class.java))
            if (ids.isEmpty()) return
            manager.updateAppWidget(ids, buildRemoteViews(app, transientStatus))
        }

        /**
         * Valid command target, or null if Bluetooth is off, either permission is
         * missing, the saved audio address is not bonded, or it has no confirmed
         * GAIA endpoint. Never reads bonded names. [SecurityException] → null.
         */
        @SuppressLint("MissingPermission")
        fun targetOrNull(context: Context): St2WidgetTarget? {
            val app = context.applicationContext
            if (!hasBluetoothPermissions(app)) return null
            val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter ?: return null
            val bondedAddresses = try {
                if (!adapter.isEnabled) return null
                adapter.bondedDevices?.map { it.address }.orEmpty()
            } catch (_: SecurityException) {
                return null
            }
            val saved = St2SelectionStore(app).loadSelectedAddress()
            val confirmed = app.getSharedPreferences(
                St2SelectionStore.CONFIRMED_ENDPOINTS_PREFS,
                Context.MODE_PRIVATE,
            )
            return widgetTargetOrNull(saved, bondedAddresses) { bonded ->
                confirmed.getString(bonded, null)
            }
        }

        fun commandIntent(context: Context, mode: AncMode): Intent =
            Intent(context.applicationContext, St2WidgetCommandService::class.java).apply {
                action = ACTION_SET_MODE
                putExtra(EXTRA_MODE, mode.name)
            }

        private fun hasBluetoothPermissions(context: Context): Boolean {
            val connect = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
            val scan = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN)
            return connect == PackageManager.PERMISSION_GRANTED &&
                scan == PackageManager.PERMISSION_GRANTED
        }

        @SuppressLint("MissingPermission")
        private fun selectedBondedDeviceName(context: Context): String? {
            if (!hasBluetoothPermissions(context)) return null
            val address = St2SelectionStore(context).loadSelectedAddress() ?: return null
            return try {
                context.getSystemService(BluetoothManager::class.java)?.adapter
                    ?.bondedDevices
                    ?.firstOrNull { it.address.equals(address, ignoreCase = true) }
                    ?.name
                    ?.takeIf { it.isNotBlank() && !it.equals(address, ignoreCase = true) }
            } catch (_: SecurityException) {
                null
            }
        }

        private fun buildRemoteViews(context: Context, status: String?): RemoteViews {
            val app = context.applicationContext
            val views = RemoteViews(app.packageName, R.layout.st2_mode_widget)
            val presentation = WidgetPresentation(St2SelectionStore(app).loadLastKnownMode(), status)
            val pairedName = selectedBondedDeviceName(app)
            val store = St2SelectionStore(app)
            val battery = if (pairedName != null) store.loadSelectedAddress()?.let(store::loadBatteryLevels) else null
            val batteryText = widgetBatteryText(battery, System.currentTimeMillis())
            val deviceCaption = pairedName?.let { app.getString(R.string.st2_widget_paired_device, it) }
                ?: app.getString(R.string.st2_widget_no_paired_device)
            val caption = if (batteryText == null) deviceCaption else
                app.getString(R.string.st2_widget_device_battery, deviceCaption, batteryText)
            val overlay = presentation.status?.takeIf { it.isNotBlank() }
            views.setTextViewText(
                R.id.st2_widget_device_status,
                if (overlay == null) caption else app.getString(R.string.st2_widget_status_caption, caption, overlay),
            )
            views.setContentDescription(
                R.id.st2_widget_root,
                if (overlay == null) app.getString(R.string.st2_widget_device_description, caption)
                else app.getString(R.string.st2_widget_device_description_status, caption, overlay),
            )
            views.setOnClickPendingIntent(
                R.id.st2_widget_root,
                activityPendingIntent(app, REQUEST_OPEN_APP),
            )
            val target = targetOrNull(app)
            views.setOnClickPendingIntent(
                R.id.st2_widget_device_status,
                activityPendingIntent(app, REQUEST_OPEN_APP),
            )
            val batteryDescription = batteryText?.let {
                app.getString(
                    R.string.st2_widget_battery_description,
                    deviceCaption,
                    battery?.left?.takeIf { level -> level > 0 }?.let { level -> app.getString(R.string.st2_widget_left_battery, level) }.orEmpty(),
                    battery?.right?.takeIf { level -> level > 0 }?.let { level -> app.getString(R.string.st2_widget_right_battery, level) }.orEmpty(),
                    android.text.format.DateFormat.getTimeFormat(app).format(java.util.Date(requireNotNull(battery).measuredAt)),
                )
            } ?: app.getString(R.string.st2_widget_open_device, deviceCaption)
            views.setContentDescription(R.id.st2_widget_device_status, if (overlay == null)
                batteryDescription else app.getString(R.string.st2_widget_status_caption, batteryDescription, overlay))
            for (mode in widgetModes()) {
                val ids = modeViewIds(mode) ?: continue
                val selected = presentation.selectedMode == mode
                val color = if (selected) R.color.st2_widget_on_selected else R.color.st2_widget_on_surface
                views.setInt(ids.target, "setBackgroundResource", if (selected)
                    R.drawable.st2_widget_button_selected else R.drawable.st2_widget_button)
                views.setTextViewText(ids.label, mode.label)
                // Resolve resources in the host so theme changes match the segment background.
                views.setColor(ids.label, "setTextColor", color)
                views.setColor(ids.icon, "setColorFilter", color)
                val actionLabel = app.getString(when (mode) {
                    AncMode.Off -> R.string.st2_widget_set_off
                    AncMode.Anc -> R.string.st2_widget_set_anc
                    AncMode.Transparency -> R.string.st2_widget_set_transparency
                    AncMode.Wind -> error("Not a widget action")
                })
                views.setContentDescription(ids.target, if (selected)
                    app.getString(R.string.st2_widget_cached_stop, actionLabel) else actionLabel)
                views.setOnClickPendingIntent(
                    ids.target,
                    if (target != null) {
                        commandPendingIntent(app, mode)
                    } else {
                        activityPendingIntent(app, REQUEST_SETUP + mode.setCode)
                    },
                )
            }
            return views
        }

        private data class StopViews(val target: Int, val icon: Int, val label: Int)

        private fun modeViewIds(mode: AncMode): StopViews? = when (mode) {
            AncMode.Off -> StopViews(R.id.st2_widget_mode_off, R.id.st2_widget_icon_off, R.id.st2_widget_label_off)
            AncMode.Anc -> StopViews(R.id.st2_widget_mode_anc, R.id.st2_widget_icon_anc, R.id.st2_widget_label_anc)
            AncMode.Transparency -> StopViews(
                R.id.st2_widget_mode_transparency, R.id.st2_widget_icon_transparency, R.id.st2_widget_label_transparency,
            )
            AncMode.Wind -> null
        }

        private fun commandPendingIntent(context: Context, mode: AncMode): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                REQUEST_COMMAND + mode.setCode,
                Intent(context, St2ModeWidget::class.java).apply {
                    action = ACTION_SET_MODE
                    putExtra(EXTRA_MODE, mode.name)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        private fun activityPendingIntent(context: Context, requestCode: Int): PendingIntent =
            PendingIntent.getActivity(
                context,
                requestCode,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        private const val REQUEST_OPEN_APP = 1
        private const val REQUEST_SETUP = 10
        private const val REQUEST_COMMAND = 20
    }
}

/** Bluetooth runs under a separate UID, so only this refresh-only receiver is exported. */
class St2WidgetBluetoothReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED ||
            intent.action == android.bluetooth.BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
            St2ModeWidget.updateAll(context)
        }
    }
}
