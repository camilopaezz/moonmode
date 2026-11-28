package dev.camilo.st2mode

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import dev.camilo.st2mode.ble.Gaia
import dev.camilo.st2mode.ble.GaiaClassic
import dev.camilo.st2mode.widget.St2ModeWidget
import java.io.IOException

/**
 * Debug-only RFCOMM session probe. It reads mode and battery, can register GAIA
 * notifications, and never sends a SET.
 *
 * adb shell am start -n dev.camilo.st2mode/.RfcommProbeActivity \
 *   --es plan "poll:600:2000" [--es address AA:BB:..]
 *
 * Plan steps, comma separated:
 * - poll:<seconds>:<intervalMs>  GET mode every interval, battery every 30 s
 * - idle:<seconds>               no traffic, socket left open
 * - get                          one GET mode and one battery read
 * - sub                          register audio-curation (08) and battery (0D) events
 * - listen:<seconds>             no requests; log unsolicited packets
 *
 * Every TX/RX frame and the socket's end are logged under the `st2-probe` tag.
 */
class RfcommProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startForegroundService(Intent(this, RfcommProbeService::class.java).putExtras(intent))
        finish()
    }
}

/**
 * Starts the widget's command service the way a widget tap does, for adb testing:
 * adb shell am start -n dev.camilo.st2mode/.WidgetCommandProbeActivity --es mode Anc
 */
class WidgetCommandProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = decodeWidgetMode(intent.getStringExtra("mode"))
        if (mode != null) startForegroundService(St2ModeWidget.commandIntent(this, mode))
        finish()
    }
}

class RfcommProbeService : Service() {
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var stopped = false
    private var worker: Thread? = null
    private var openedAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("probe", "Probe", NotificationManager.IMPORTANCE_LOW))
        startForeground(
            2,
            Notification.Builder(this, "probe").setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("RFCOMM probe").setOngoing(true).build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        if (worker?.isAlive == true) {
            log("already running")
            return START_NOT_STICKY
        }
        val plan = intent?.getStringExtra("plan") ?: "get"
        val address = intent?.getStringExtra("address") ?: St2SelectionStore(this).loadSelectedAddress()
            ?: bondedSpaceTravel()
        worker = Thread({ run(address, plan) }, "st2-probe").apply { start() }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun run(address: String?, plan: String) {
        try {
            if (address == null) { log("no address"); return }
            val adapter = getSystemService(BluetoothManager::class.java).adapter
            val device = adapter.getRemoteDevice(address)
            log("plan=$plan")
            val start = SystemClock.elapsedRealtime()
            val s = device.createRfcommSocketToServiceRecord(GaiaClassic.SPP_UUID)
            socket = s
            s.connect()
            openedAt = SystemClock.elapsedRealtime()
            log("connected connectMs=${openedAt - start}")
            startReader(s)
            for (step in plan.split(',')) {
                if (stopped) break
                val parts = step.trim().split(':')
                log("step ${parts.joinToString(":")}")
                when (parts[0]) {
                    "poll" -> {
                        val until = SystemClock.elapsedRealtime() + parts[1].toLong() * 1000
                        val interval = parts.getOrNull(2)?.toLong() ?: 2000L
                        var lastBattery = 0L
                        while (!stopped && SystemClock.elapsedRealtime() < until) {
                            send(Gaia.getCurrentMode())
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastBattery >= 30_000) {
                                Thread.sleep(150)
                                send(Gaia.getBatteryLevels())
                                lastBattery = now
                            }
                            Thread.sleep(interval)
                        }
                    }
                    "idle", "listen" -> sleepUnlessStopped(parts[1].toLong() * 1000)
                    "get" -> {
                        send(Gaia.getCurrentMode()); Thread.sleep(500)
                        send(Gaia.getBatteryLevels()); Thread.sleep(500)
                    }
                    "sub" -> {
                        send(Gaia.frame(Gaia.commandValue(0, 0, 7), byteArrayOf(8))); Thread.sleep(800)
                        send(Gaia.frame(Gaia.commandValue(0, 0, 7), byteArrayOf(13))); Thread.sleep(800)
                    }
                    else -> log("unknown step")
                }
            }
            log("plan done aliveMs=${SystemClock.elapsedRealtime() - openedAt}")
        } catch (e: Exception) {
            log("worker ended ${e.javaClass.simpleName}: ${e.message} aliveMs=${aliveMs()}")
        } finally {
            runCatching { socket?.close() }
            stopSelf()
        }
    }

    @SuppressLint("MissingPermission")
    private fun bondedSpaceTravel(): String? =
        getSystemService(BluetoothManager::class.java).adapter.bondedDevices
            .firstOrNull { it.name?.contains("SPACE TRAVEL", ignoreCase = true) == true }?.address

    private fun startReader(s: BluetoothSocket) {
        Thread({
            val parser = GaiaClassic.Reader()
            val buf = ByteArray(1024)
            try {
                val input = s.inputStream
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) { log("rx EOF aliveMs=${aliveMs()}"); break }
                    parser.feed(buf.copyOf(n)).forEach { log("RX ${hex(it)}") }
                }
            } catch (e: IOException) {
                log("rx closed ${e.message} aliveMs=${aliveMs()}")
            }
        }, "st2-probe-rx").apply { isDaemon = true; start() }
    }

    private fun send(ble: ByteArray) {
        log("TX ${hex(ble)}")
        socket!!.outputStream.write(GaiaClassic.frame(ble))
    }

    private fun sleepUnlessStopped(ms: Long) {
        val until = SystemClock.elapsedRealtime() + ms
        while (!stopped && SystemClock.elapsedRealtime() < until) Thread.sleep(500)
    }

    private fun aliveMs() = if (openedAt == 0L) -1 else SystemClock.elapsedRealtime() - openedAt

    private fun hex(b: ByteArray) = b.joinToString(" ") { "%02X".format(it) }

    private fun log(message: String) { Log.i("st2-probe", message) }

    override fun onDestroy() {
        stopped = true
        runCatching { socket?.close() }
        log("service destroyed aliveMs=${aliveMs()}")
        super.onDestroy()
    }
}
