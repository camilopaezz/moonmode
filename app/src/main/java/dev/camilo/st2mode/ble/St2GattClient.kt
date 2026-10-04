package dev.camilo.st2mode.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import dev.camilo.st2mode.St2SelectionStore
import dev.camilo.st2mode.resolveBondedSelection
import dev.camilo.st2mode.widget.St2ModeWidget
import java.util.ArrayDeque
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class BondedDevice(
    val address: String,
    val name: String,
    val preferred: Boolean,
)

data class BleEndpoint(val address: String, val name: String)

internal fun matchesEndpointName(selected: String?, advertised: String?): Boolean =
    !selected.isNullOrBlank() && advertised?.equals(selected, ignoreCase = true) == true

internal fun endpointChoices(selected: String?, seen: List<BleEndpoint>): List<BleEndpoint> =
    seen.filter { matchesEndpointName(selected, it.name) }
        .distinctBy { it.address }
        .sortedBy { it.address }

internal fun cachedEndpoint(bondedAddress: String?, read: (String) -> String?): String? =
    bondedAddress?.let(read)

internal enum class CommandKind { MODE_GET, MODE_SET, BATTERY_GET }

internal sealed class GattOp {
    data class WriteCccd(val charUuid: UUID) : GattOp()
    data class WriteCommand(
        val bytes: ByteArray,
        val sending: Boolean,
        val mode: AncMode? = null,
        val completion: CompletableDeferred<Boolean>? = null,
        val kind: CommandKind = if (mode != null) CommandKind.MODE_SET else CommandKind.MODE_GET,
    ) : GattOp() {
        val isSet: Boolean get() = mode != null
        val isGet: Boolean get() = kind == CommandKind.MODE_GET
    }
}

/** Commands registered before discovery belong to one selected device and connection attempt. */
internal class PendingModeWrites {
    private data class Pending(val address: String, val attempt: Int, val op: GattOp.WriteCommand)
    private val pending = mutableListOf<Pending>()

    fun add(address: String, attempt: Int, op: GattOp.WriteCommand) {
        pending.add(Pending(address, attempt, op))
    }

    fun take(address: String?, attempt: Int): List<GattOp.WriteCommand> {
        val result = pending.filter {
            val matches = it.address == address && it.attempt == attempt
            if (!matches) it.op.completion?.complete(false)
            matches && it.op.completion?.isCancelled != true
        }.map { it.op }
        pending.clear()
        return result
    }

    fun clear() {
        pending.forEach { it.op.completion?.complete(false) }
        pending.clear()
    }
}

/** The queue and its active operation share one owner so a write callback cannot pick a later mode. */
internal class ModeWrites {
    private val queue = ArrayDeque<GattOp>()
    var inFlight: GattOp? = null
        private set

    fun enqueue(op: GattOp) { queue.addLast(op) }

    /** A new user command overtakes setup and reads while preserving earlier SET order. */
    fun enqueuePriority(op: GattOp.WriteCommand) {
        val earlierSets = queue.filter { it is GattOp.WriteCommand && it.isSet }
        queue.removeAll(earlierSets.toSet())
        queue.addFirst(op)
        earlierSets.asReversed().forEach(queue::addFirst)
    }

    /** Called once after discovery, before the queue is pumped. */
    fun enqueueServiceSetup(commands: List<GattOp.WriteCommand>) {
        commands.forEach(::enqueue)
        enqueue(GattOp.WriteCccd(Gaia.CHAR_RESPONSE))
        enqueue(GattOp.WriteCccd(Gaia.CHAR_DATA))
    }

    fun startNext(): GattOp? {
        if (inFlight != null) return null
        while (true) {
            val next = queue.pollFirst() ?: return null
            if (next is GattOp.WriteCommand && next.completion?.isCancelled == true) continue
            inFlight = next
            return next
        }
    }

    fun hasSet(): Boolean {
        val current = inFlight
        if (current is GattOp.WriteCommand && current.isSet) return true
        return queue.any { it is GattOp.WriteCommand && it.isSet }
    }

    fun dropQueuedGets(): Boolean {
        val iterator = queue.iterator()
        var removed = false
        while (iterator.hasNext()) {
            val op = iterator.next()
            if (op is GattOp.WriteCommand && op.isGet) {
                iterator.remove()
                removed = true
            }
        }
        return removed
    }

    fun finish(success: Boolean): AncMode? {
        val completed = inFlight as? GattOp.WriteCommand
        inFlight = null
        completed?.completion?.complete(success)
        if (!success && completed?.isSet == true) clear()
        return if (success) completed?.mode else null
    }

    fun clear() {
        (inFlight as? GattOp.WriteCommand)?.completion?.complete(false)
        queue.forEach { (it as? GattOp.WriteCommand)?.completion?.complete(false) }
        queue.clear()
        inFlight = null
    }
}

/** One outstanding read; SET invalidates older reads and waits for the earbuds to settle. */
internal class ModePoll(private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 }) {
    var enabled: Boolean = false
        private set
    var generation: Int = 0
        private set
    var awaitingGet: Boolean = false
        private set
    private var sentGeneration: Int = -1
    private var expectedMode: AncMode? = null
    private var pollAfterMs: Long = Long.MIN_VALUE
    private var confirmByMs: Long = Long.MIN_VALUE
    private var matchingReports = 0

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    fun resetOutstanding() {
        awaitingGet = false
        sentGeneration = -1
        generation++
        expectedMode = null
        pollAfterMs = Long.MIN_VALUE
        confirmByMs = Long.MIN_VALUE
        matchingReports = 0
    }

    fun shouldEnqueueGet(ready: Boolean, setBusy: Boolean): Boolean =
        enabled && ready && !setBusy && !awaitingGet && nowMs() >= pollAfterMs

    fun onGetEnqueued() {
        awaitingGet = true
        sentGeneration = generation
    }

    fun onGetSettled() {
        awaitingGet = false
        sentGeneration = -1
    }

    fun onSetRequested() {
        generation++
    }

    fun onSetCompleted(mode: AncMode) {
        expectedMode = mode
        matchingReports = 0
        val now = nowMs()
        // The GATT acknowledgement confirms delivery, not that the mode has been applied.
        pollAfterMs = now + 2_000
        confirmByMs = now + 10_000
    }

    fun onQueuedGetDropped() {
        awaitingGet = false
        sentGeneration = -1
    }

    fun acceptGetReport(mode: AncMode): Boolean {
        if (!awaitingGet) return false
        val accept = sentGeneration == generation
        awaitingGet = false
        sentGeneration = -1
        if (!accept) return false
        val expected = expectedMode ?: return true
        if (nowMs() >= confirmByMs) {
            // A failed device command must not hide the actual mode indefinitely.
            expectedMode = null
            return true
        }
        if (mode != expected) {
            matchingReports = 0
            return false
        }
        // A rapid A -> B -> A sequence can produce an early report of the first A.
        // Require consecutive polls of the latest mode before trusting other changes again.
        matchingReports++
        if (matchingReports >= 2) expectedMode = null
        return true
    }

    fun shouldArmGetTimeout(): Boolean = awaitingGet
}

internal fun decodeModeReport(value: ByteArray): AncMode? {
    val packet = Gaia.parse(value) ?: return null
    if (!Gaia.isModeReportRx(packet.commandValue)) return null
    val modeByte = packet.payload.firstOrNull()?.toInt() ?: return null
    return AncMode.fromGetPayload(modeByte)
}

data class ClientState(
    val status: String = "disconnected",
    val errorMessage: String? = null,
    val currentMode: AncMode? = null,
    val lastKnownMode: AncMode? = null,
    val bonded: List<BondedDevice> = emptyList(),
    val selectedAddress: String? = null,
    val endpoints: List<BleEndpoint> = emptyList(),
    val ready: Boolean = false,
)

class St2GattClient(private val context: Context) {
    private val confirmedEndpoints =
        context.getSharedPreferences(St2SelectionStore.CONFIRMED_ENDPOINTS_PREFS, Context.MODE_PRIVATE)
    private val selectionStore = St2SelectionStore(context)
    private val main = Handler(Looper.getMainLooper())
    private val workerThread = HandlerThread("st2-gatt").apply { start() }
    private val worker = Handler(workerThread.looper)

    private val _state = MutableStateFlow(ClientState(lastKnownMode = selectionStore.loadLastKnownMode()))
    val state: StateFlow<ClientState> = _state.asStateFlow()

    private val devicesByAddress = mutableMapOf<String, BluetoothDevice>()
    private var gatt: BluetoothGatt? = null
    private var commandChar: BluetoothGattCharacteristic? = null
    private var ready = false
    private var cccdRemaining = 0
    private var epoch = 0
    private var closing = false
    private var pendingConnect: BluetoothDevice? = null
    private var scanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null
    private var scanGeneration = 0
    @Volatile private var attempt = 0
    private var gattAttempt = 0
    private var activeBondedAddress: String? = null
    private var pendingConfirmation: String? = null
    private var connectingCached = false
    private var allowScanFallback = true
    private val scanDevices = mutableMapOf<String, Pair<BluetoothDevice, String>>()

    private val writes = ModeWrites()
    private val pendingModes = PendingModeWrites()
    private var connectionStartedMs = 0L
    private var earlySetCompleted = false

    private fun logLatency(stage: String) {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            Log.d("st2-latency", "$stage elapsedMs=${SystemClock.elapsedRealtime() - connectionStartedMs}")
        }
    }
    private val poll = ModePoll()
    private var writeGapPending = false
    private val modeReadWaiters = mutableListOf<CompletableDeferred<Boolean>>()

    private fun finishModeReads(success: Boolean) {
        modeReadWaiters.forEach { it.complete(success) }
        modeReadWaiters.clear()
    }

    /** One explicit GET without enabling the activity's continuous polling loop. */
    suspend fun refreshModeAndAwait(): Boolean {
        val completion = CompletableDeferred<Boolean>()
        if (!worker.post {
            if (!ready || writes.hasSet() || completion.isCancelled) {
                completion.complete(false)
                return@post
            }
            modeReadWaiters.add(completion)
            if (!poll.awaitingGet) {
                poll.onGetEnqueued()
                enqueue(GattOp.WriteCommand(Gaia.getCurrentMode(), sending = false))
            }
            worker.postDelayed({
                modeReadWaiters.remove(completion)
                completion.complete(false)
            }, GET_RESPONSE_TIMEOUT_MS + 1_000L)
        }) return false
        return try {
            completion.await()
        } finally {
            completion.cancel()
            worker.post { modeReadWaiters.remove(completion) }
        }
    }
    private var batteryRequest: CompletableDeferred<Boolean>? = null
    private val batteryTimeoutRunnable = Runnable { finishBatteryRequest(false) }

    private fun finishBatteryRequest(success: Boolean) {
        worker.removeCallbacks(batteryTimeoutRunnable)
        batteryRequest?.complete(success)
        batteryRequest = null
    }

    private fun requestBattery(): CompletableDeferred<Boolean> {
        batteryRequest?.let { return it }
        val result = CompletableDeferred<Boolean>()
        if (!ready) {
            result.complete(false)
            return result
        }
        batteryRequest = result
        enqueue(GattOp.WriteCommand(
            Gaia.getBatteryLevels(), sending = false, kind = CommandKind.BATTERY_GET,
        ))
        // Bound queueing plus the response wait; ANC commands keep their own timeout.
        worker.postDelayed(batteryTimeoutRunnable, BATTERY_RESPONSE_TIMEOUT_MS)
        return result
    }

    suspend fun refreshBatteryAndAwait(): Boolean {
        val request = CompletableDeferred<CompletableDeferred<Boolean>>()
        if (!worker.post { request.complete(requestBattery()) }) return false
        return request.await().await()
    }

    private val pollRunnable: Runnable = Runnable {
        maybeEnqueuePollGet()
        if (poll.enabled && ready) worker.postDelayed(pollRunnable, POLL_INTERVAL_MS)
    }

    private var getTimeoutEpoch = 0
    private val getTimeoutRunnable = Runnable {
        if (epoch != getTimeoutEpoch) return@Runnable
        poll.onGetSettled()
        finishModeReads(false)
    }

    fun hasPermissions(): Boolean {
        val connect = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
        val scan = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN)
        return connect == PackageManager.PERMISSION_GRANTED && scan == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    fun refreshBonded() {
        if (!hasPermissions()) {
            setError("permission denied")
            return
        }
        val adapter = bluetoothAdapter()
        if (adapter == null) {
            setError("connect failed")
            return
        }
        val list = adapter.bondedDevices.orEmpty().map { device ->
            devicesByAddress[device.address] = device
            val name = device.name?.ifBlank { null } ?: device.address
            BondedDevice(
                address = device.address,
                name = name,
                preferred = isPreferred(device.name),
            )
        }.sortedWith(
            compareByDescending<BondedDevice> { it.preferred }.thenBy { it.name },
        )
        val bondedAddresses = list.map { it.address }
        val saved = selectionStore.loadSelectedAddress()
        val selected = resolveBondedSelection(_state.value.selectedAddress, saved, bondedAddresses)
            ?: list.firstOrNull { it.preferred }?.address
            ?: list.firstOrNull()?.address
        if (selected != null && !selected.equals(saved, ignoreCase = true)) {
            selectionStore.saveSelectedAddress(selected)
            selectionStore.clearLastKnownMode()
        }
        val lastKnown = selectionStore.loadLastKnownMode()
        hop {
            _state.update {
                it.copy(bonded = list, selectedAddress = selected, lastKnownMode = lastKnown)
            }
        }
    }

    fun select(address: String) {
        worker.post {
            if (_state.value.selectedAddress != address) {
                cancelAttempt()
                selectionStore.saveSelectedAddress(address)
                selectionStore.clearLastKnownMode()
                hop {
                    _state.update {
                        it.copy(
                            selectedAddress = address,
                            status = "disconnected",
                            errorMessage = null,
                            currentMode = null,
                            lastKnownMode = null,
                            ready = false,
                            endpoints = emptyList(),
                        )
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(allowScan: Boolean = true) {
        if (!hasPermissions()) {
            setError("permission denied")
            return
        }
        val adapter = bluetoothAdapter()
        if (adapter == null || !adapter.isEnabled) {
            setError("connect failed")
            return
        }
        val address = _state.value.selectedAddress
        val device = address?.let { devicesByAddress[it] }
        if (device == null) {
            setError("connect failed")
            return
        }
        val bondedAddress = address ?: return
        val status = _state.value.status
        if (status != "disconnected" && status != "error") return
        val cached = cachedEndpoint(bondedAddress) { confirmedEndpoints.getString(it, null) }
        if (cached == null && !allowScan) {
            setError("Open the app to confirm the BLE endpoint")
            return
        }
        _state.update {
            it.copy(
                status = if (cached == null) "scanning" else "connecting",
                errorMessage = null,
                currentMode = null,
                ready = false,
                endpoints = emptyList(),
            )
        }
        worker.post {
            allowScanFallback = allowScan
            if (cached == null) {
                startScan(device.name, bondedAddress)
            } else {
                val endpoint = runCatching { adapter.getRemoteDevice(cached) }.getOrNull()
                if (endpoint == null) {
                    if (recoverCachedEndpoint(bondedAddress, allowScan) {
                        confirmedEndpoints.edit().remove(it).apply()
                    }) {
                        hop { _state.update { it.copy(status = "scanning") } }
                        startScan(device.name, bondedAddress)
                    } else {
                        setError("Open the app to confirm the BLE endpoint")
                    }
                } else {
                    attempt++
                    activeBondedAddress = bondedAddress
                    pendingConfirmation = null
                    connectingCached = true
                    connectOnWorker(endpoint)
                }
            }
        }
    }

    fun confirmEndpoint(address: String) {
        worker.post {
            if (_state.value.status != "select endpoint") return@post
            val device = scanDevices[address]?.first ?: return@post
            pendingConfirmation = address
            scanDevices.clear()
            val currentAttempt = attempt
            hop {
                if (attempt == currentAttempt) {
                    _state.update { it.copy(status = "connecting", endpoints = emptyList()) }
                }
            }
            connectOnWorker(device)
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        worker.post {
            cancelAttempt()
            hop {
                _state.update {
                    it.copy(
                        status = "disconnected",
                        errorMessage = null,
                        currentMode = null,
                        ready = false,
                        endpoints = emptyList(),
                    )
                }
            }
        }
    }

    /** Recheck ownership when cleanup executes, and never close a later connection attempt. */
    fun disconnectAfterRefresh(runIfIdle: (() -> Unit) -> Unit) {
        val refreshAttempt = attempt
        worker.post {
            if (attempt != refreshAttempt) return@post
            runIfIdle {
                cancelAttempt()
                // Publish under the same ownership lock before a new command can acquire this client.
                _state.update { it.copy(status = "disconnected", errorMessage = null,
                    currentMode = null, ready = false, endpoints = emptyList()) }
            }
        }
    }

    /** Reset only this device's control endpoint, after closing its active session. */
    fun resetSelectedEndpoint() {
        val address = _state.value.selectedAddress ?: return
        worker.post {
            if (_state.value.selectedAddress != address) return@post
            cancelAttempt()
            confirmedEndpoints.edit().remove(address).apply()
            hop {
                _state.update { it.copy(status = "disconnected", errorMessage = null,
                    currentMode = null, ready = false, endpoints = emptyList()) }
            }
        }
    }

    /** Register the requested mode before connecting, so discovery can send it before subscriptions. */
    suspend fun connectAndSetModeAndAwait(mode: AncMode, expectedBondedAddress: String): Boolean {
        val completion = CompletableDeferred<Boolean>()
        if (!worker.post {
            val status = _state.value.status
            if (completion.isCancelled || _state.value.selectedAddress != expectedBondedAddress ||
                status !in setOf("disconnected", "error", "connecting", "connected", "sending")) {
                completion.complete(false)
                return@post
            }
            val op = GattOp.WriteCommand(
                Gaia.setMode(mode.setCode), sending = true, mode = mode, completion = completion,
            )
            poll.onSetRequested()
            if (writes.dropQueuedGets()) poll.onQueuedGetDropped()
            if (ready) {
                enqueue(op)
            } else if (commandChar != null && !closing &&
                (status == "connecting" || status == "sending")) {
                writes.enqueuePriority(op)
                pump()
            } else {
                val starting = _state.value.status == "disconnected" || _state.value.status == "error"
                pendingModes.add(expectedBondedAddress, if (starting) attempt + 1 else attempt, op)
                if (starting) hop {
                    if (!completion.isCancelled && _state.value.selectedAddress == expectedBondedAddress) {
                        connect(allowScan = false)
                    } else {
                        completion.complete(false)
                    }
                }
            }
        }) return false
        return try {
            completion.await()
        } finally {
            completion.cancel()
        }
    }

    fun setMode(mode: AncMode) = enqueueMode(mode, null)

    /** Wait for this command's write callback, not a cached mode or another writer's result. */
    suspend fun setModeAndAwait(mode: AncMode): Boolean {
        val completion = CompletableDeferred<Boolean>()
        enqueueMode(mode, completion)
        return try {
            completion.await()
        } finally {
            completion.cancel()
        }
    }

    private fun enqueueMode(mode: AncMode, completion: CompletableDeferred<Boolean>?) {
        val posted = worker.post {
            if (!ready || completion?.isCancelled == true) {
                completion?.complete(false)
                return@post
            }
            poll.onSetRequested()
            if (writes.dropQueuedGets()) poll.onQueuedGetDropped()
            enqueue(GattOp.WriteCommand(
                Gaia.setMode(mode.setCode), sending = true, mode = mode, completion = completion,
            ))
        }
        if (!posted) completion?.complete(false)
    }

    fun setPollingEnabled(enabled: Boolean) {
        worker.post {
            poll.setEnabled(enabled)
            worker.removeCallbacks(pollRunnable)
            if (enabled && ready) {
                maybeEnqueuePollGet()
                worker.postDelayed(pollRunnable, POLL_INTERVAL_MS)
            }
        }
    }

    fun release() {
        worker.post {
            cancelAttempt()
            closeNow()
            workerThread.quitSafely()
        }
    }

    private fun bluetoothAdapter() =
        context.getSystemService(BluetoothManager::class.java)?.adapter

    private fun isPreferred(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        return name.contains("SPACE TRAVEL", ignoreCase = true) ||
            name.contains("ST2", ignoreCase = true)
    }

    @SuppressLint("MissingPermission")
    private fun startScan(selectedName: String?, bondedAddress: String) {
        stopScan()
        scanDevices.clear()
        activeBondedAddress = bondedAddress
        pendingConfirmation = null
        connectingCached = false
        val currentAttempt = ++attempt
        hopSession {
            _state.update {
                it.copy(status = "scanning", errorMessage = null, ready = false, endpoints = emptyList())
            }
        }
        val adapter = bluetoothAdapter()
        if (!hasPermissions() || adapter == null || !adapter.isEnabled) {
            setError("Bluetooth unavailable")
            return
        }
        val leScanner = adapter.bluetoothLeScanner
        if (leScanner == null) {
            setError("BLE scan unavailable")
            return
        }
        val generation = ++scanGeneration
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                worker.post {
                    if (generation != scanGeneration || attempt != currentAttempt) return@post
                    val name = result.scanRecord?.deviceName
                        ?: runCatching { result.device.name }.getOrNull()
                    if (name != null && matchesEndpointName(selectedName, name)) {
                        scanDevices[result.device.address] = result.device to name
                    }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                worker.post {
                    if (generation != scanGeneration || attempt != currentAttempt) return@post
                    stopScan()
                    scanDevices.clear()
                    setError("BLE scan failed ($errorCode)")
                }
            }
        }
        scanner = leScanner
        scanCallback = callback
        try {
            leScanner.startScan(callback)
        } catch (_: SecurityException) {
            stopScan()
            setError("permission denied")
            return
        } catch (_: IllegalStateException) {
            stopScan()
            setError("BLE scan unavailable")
            return
        }
        worker.postDelayed({
            if (generation != scanGeneration || attempt != currentAttempt) return@postDelayed
            stopScan()
            val endpoints = endpointChoices(
                selectedName,
                scanDevices.values.map { (device, name) -> BleEndpoint(device.address, name) },
            )
            hop {
                if (attempt == currentAttempt) {
                    _state.update {
                        if (endpoints.isEmpty()) {
                            it.copy(status = "error", errorMessage = "BLE control device not found")
                        } else {
                            it.copy(status = "select endpoint", endpoints = endpoints)
                        }
                    }
                }
            }
        }, SCAN_TIMEOUT_MS)
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        scanGeneration++
        val activeScanner = scanner
        val callback = scanCallback
        scanner = null
        scanCallback = null
        if (activeScanner != null && callback != null) {
            runCatching { activeScanner.stopScan(callback) }
        }
    }

    private fun cancelAttempt() {
        attempt++
        stopScan()
        scanDevices.clear()
        pendingConnect = null
        activeBondedAddress = null
        pendingConfirmation = null
        connectingCached = false
        beginDisconnect()
    }

    private fun scanAfterCachedFailure(): Boolean {
        if (!connectingCached) return false
        val bondedAddress = activeBondedAddress ?: return false
        connectingCached = false
        pendingConnect = null
        if (!recoverCachedEndpoint(bondedAddress, allowScanFallback) {
            confirmedEndpoints.edit().remove(it).apply()
        }) return false
        if (gatt != null && !closing) beginDisconnect()
        val name = _state.value.bonded.firstOrNull { it.address == bondedAddress }?.name
        startScan(name, bondedAddress)
        return true
    }

    private fun hop(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private fun hopSession(block: () -> Unit) {
        val currentAttempt = attempt
        hop { if (attempt == currentAttempt) block() }
    }

    private fun setError(message: String) {
        ready = false
        if (Looper.myLooper() == workerThread.looper) pendingModes.clear()
        else worker.post { pendingModes.clear() }
        val update = {
            _state.update {
                it.copy(status = "error", errorMessage = message, ready = false)
            }
        }
        if (Looper.myLooper() == workerThread.looper) hopSession(update) else hop(update)
    }

    private fun isCurrent(g: BluetoothGatt): Boolean = gatt === g

    private fun isCurrentSession(g: BluetoothGatt): Boolean = gatt === g && !closing

    @SuppressLint("MissingPermission")
    private fun closeInternal(preservePendingModes: Boolean = false) {
        epoch++
        if (!preservePendingModes) pendingModes.clear()
        earlySetCompleted = false
        finishModeReads(false)
        finishBatteryRequest(false)
        writes.clear()
        writeGapPending = false
        ready = false
        cccdRemaining = 0
        commandChar = null
        worker.removeCallbacks(pollRunnable)
        worker.removeCallbacks(getTimeoutRunnable)
        poll.resetOutstanding()
    }

    @SuppressLint("MissingPermission")
    private fun beginDisconnect(preservePendingModes: Boolean = false) {
        if (closing) {
            if (!preservePendingModes) pendingModes.clear()
            return
        }
        closeInternal(preservePendingModes)
        val g = gatt ?: return
        closing = true
        runCatching { g.disconnect() }
        val captured = epoch
        worker.postDelayed({
            if (epoch != captured) return@postDelayed
            if (gatt === g) finishClose(g)
        }, DISCONNECT_TIMEOUT_MS)
    }

    @SuppressLint("MissingPermission")
    private fun finishClose(g: BluetoothGatt) {
        runCatching { g.close() }
        if (gatt !== g) return
        gatt = null
        scheduleSettle()
    }

    private fun scheduleSettle() {
        closing = true
        val captured = epoch
        worker.postDelayed({
            if (epoch != captured) return@postDelayed
            closing = false
            val device = pendingConnect
            pendingConnect = null
            if (device != null) openGatt(device)
        }, CLOSE_SETTLE_MS)
    }

    @SuppressLint("MissingPermission")
    private fun closeNow() {
        closeInternal()
        closing = false
        gatt?.let { g ->
            runCatching { g.disconnect() }
            runCatching { g.close() }
        }
        gatt = null
    }

    @SuppressLint("MissingPermission")
    private fun connectOnWorker(device: BluetoothDevice) {
        pendingConnect = device
        if (gatt != null || closing) {
            if (gatt != null && !closing) beginDisconnect(preservePendingModes = true)
            return
        }
        pendingConnect = null
        openGatt(device)
    }

    @SuppressLint("MissingPermission")
    private fun openGatt(device: BluetoothDevice) {
        closing = false
        gattAttempt = attempt
        connectionStartedMs = SystemClock.elapsedRealtime()
        logLatency("connect-start")
        val opened = try {
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (_: SecurityException) {
            setError("permission denied")
            return
        }
        gatt = opened
        if (opened == null) {
            if (!scanAfterCachedFailure()) setError("connect failed")
            return
        }
        val currentAttempt = attempt
        worker.postDelayed({
            if (attempt == currentAttempt && gatt === opened && !ready && !closing) {
                beginDisconnect()
                if (!scanAfterCachedFailure()) setError("BLE connection timed out")
            }
        }, CONNECT_TIMEOUT_MS)
    }

    private fun enqueue(op: GattOp) {
        writes.enqueue(op)
        pump()
    }

    private fun maybeEnqueuePollGet() {
        if (!poll.shouldEnqueueGet(ready, writes.hasSet())) return
        poll.onGetEnqueued()
        enqueue(GattOp.WriteCommand(Gaia.getCurrentMode(), sending = false))
    }

    private fun startPollLoop() {
        worker.removeCallbacks(pollRunnable)
        if (!poll.enabled) return
        worker.postDelayed(pollRunnable, POLL_INTERVAL_MS)
    }

    private fun scheduleGetTimeout() {
        getTimeoutEpoch = epoch
        worker.removeCallbacks(getTimeoutRunnable)
        worker.postDelayed(getTimeoutRunnable, GET_RESPONSE_TIMEOUT_MS)
    }

    @SuppressLint("MissingPermission")
    private fun pump() {
        if (writeGapPending || writes.inFlight != null) return
        val g = gatt ?: return
        val op = writes.startNext() ?: return
        val ok = when (op) {
            is GattOp.WriteCccd -> writeCccd(g, op.charUuid)
            is GattOp.WriteCommand -> writeCommand(g, op.bytes, op.sending)
        }
        if (!ok) {
            writes.finish(success = false)
            if (op is GattOp.WriteCommand && op.kind == CommandKind.BATTERY_GET) {
                finishBatteryRequest(false)
                onOpFinished()
            } else if (op is GattOp.WriteCommand && op.isGet) {
                poll.onGetSettled()
                finishModeReads(false)
                onOpFinished()
            } else {
                if (!ready) beginDisconnect()
                if (!scanAfterCachedFailure()) setError("connect failed")
            }
        }
    }

    private fun onOpFinished() {
        writeGapPending = true
        val captured = epoch
        worker.postDelayed({
            if (epoch != captured) return@postDelayed
            writeGapPending = false
            pump()
        }, WRITE_GAP_MS)
    }

    @SuppressLint("MissingPermission")
    private fun writeCccd(g: BluetoothGatt, charUuid: UUID): Boolean {
        val ch = g.getService(Gaia.SERVICE)?.getCharacteristic(charUuid) ?: return false
        if (!g.setCharacteristicNotification(ch, true)) return false
        val cccd = ch.getDescriptor(Gaia.CCCD) ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            g.writeDescriptor(cccd)
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeCommand(g: BluetoothGatt, bytes: ByteArray, sending: Boolean): Boolean {
        val ch = commandChar ?: return false
        if (sending) {
            logLatency("mode-write-start")
            hopSession { _state.update { it.copy(status = "sending", errorMessage = null) } }
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(ch, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                BluetoothStatusCodes.SUCCESS
        } else {
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            ch.value = bytes
            @Suppress("DEPRECATION")
            g.writeCharacteristic(ch)
        }
    }

    private fun handleRx(value: ByteArray) {
        val battery = decodeBatteryReport(value, System.currentTimeMillis())
        if (battery != null) {
            val address = activeBondedAddress ?: return
            if (!address.equals(selectionStore.loadSelectedAddress(), ignoreCase = true)) return
            selectionStore.saveBatteryLevels(address, battery)
            finishBatteryRequest(true)
            hopSession { St2ModeWidget.updateAll(context, preserveStatus = true) }
            return
        }
        if (Gaia.parse(value)?.commandValue == Gaia.GET_BATTERY_LEVELS_ERROR) {
            finishBatteryRequest(false)
            return
        }
        val mode = decodeModeReport(value) ?: return
        val accept = poll.acceptGetReport(mode)
        if (accept) logLatency("mode-report-${mode.label}")
        worker.removeCallbacks(getTimeoutRunnable)
        if (!accept) {
            finishModeReads(false)
            return
        }
        val modeChanged = selectionStore.loadLastKnownMode() != mode
        selectionStore.saveLastKnownMode(mode)
        finishModeReads(true)
        hopSession {
            _state.update {
                it.copy(
                    currentMode = mode,
                    lastKnownMode = mode,
                    status = if (it.status == "error") it.status else "connected",
                    errorMessage = if (it.status == "error") it.errorMessage else null,
                )
            }
            if (modeChanged) St2ModeWidget.updateAll(context, preserveStatus = true)
        }
    }

    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            worker.post {
                if (!isCurrent(g)) return@post
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    if (closing) return@post
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        beginDisconnect()
                        if (!scanAfterCachedFailure()) setError("connect failed")
                        return@post
                    }
                    logLatency("connected")
                    if (!g.discoverServices()) {
                        beginDisconnect()
                        if (!scanAfterCachedFailure()) setError("connect failed")
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    val activeAttempt = attempt == gattAttempt
                    val wasClosing = closing
                    if (!closing) closeInternal()
                    finishClose(g)
                    if (activeAttempt && scanAfterCachedFailure()) return@post
                    if (activeAttempt && pendingConnect == null) {
                        val currentAttempt = attempt
                        hop {
                            if (attempt == currentAttempt) {
                                _state.update { current ->
                                    when {
                                        current.status == "error" ->
                                            current.copy(ready = false, currentMode = null)
                                        status != BluetoothGatt.GATT_SUCCESS && !wasClosing ->
                                            current.copy(
                                                status = "error",
                                                errorMessage = "BLE connection failed ($status)",
                                                ready = false,
                                                currentMode = null,
                                            )
                                        else -> current.copy(
                                            status = "disconnected",
                                            errorMessage = null,
                                            currentMode = null,
                                            ready = false,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            worker.post {
                if (!isCurrentSession(g)) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    beginDisconnect()
                    if (!scanAfterCachedFailure()) setError("connect failed")
                    return@post
                }
                val service = g.getService(Gaia.SERVICE)
                if (service == null) {
                    beginDisconnect()
                    if (!scanAfterCachedFailure()) setError("GAIA service missing")
                    return@post
                }
                val cmd = service.getCharacteristic(Gaia.CHAR_COMMAND)
                val response = service.getCharacteristic(Gaia.CHAR_RESPONSE)
                val data = service.getCharacteristic(Gaia.CHAR_DATA)
                if (cmd == null || response == null || data == null) {
                    beginDisconnect()
                    if (!scanAfterCachedFailure()) setError("GAIA service missing")
                    return@post
                }
                commandChar = cmd
                logLatency("services-discovered")
                cccdRemaining = 2
                val commands = pendingModes.take(activeBondedAddress, attempt)
                writes.enqueueServiceSetup(commands)
                pump()
            }
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            worker.post {
                val active = writes.inFlight as? GattOp.WriteCccd
                if (!isCurrentSession(g) || active?.charUuid != descriptor.characteristic.uuid) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    beginDisconnect()
                    if (!scanAfterCachedFailure()) setError("connect failed")
                    return@post
                }
                if (descriptor.uuid == Gaia.CCCD) {
                    cccdRemaining = (cccdRemaining - 1).coerceAtLeast(0)
                    if (cccdRemaining == 0) {
                        ready = true
                        val bondedAddress = activeBondedAddress
                        val endpointAddress = pendingConfirmation
                        if (bondedAddress != null && endpointAddress != null) {
                            confirmedEndpoints.edit().putString(bondedAddress, endpointAddress).apply()
                        }
                        pendingConfirmation = null
                        connectingCached = false
                        hopSession {
                            _state.update {
                                it.copy(status = "connected", errorMessage = null, ready = true)
                            }
                        }
                        if (!earlySetCompleted && !writes.hasSet()) {
                            poll.onGetEnqueued()
                            enqueue(GattOp.WriteCommand(Gaia.getCurrentMode(), sending = false))
                        }
                        requestBattery()
                        startPollLoop()
                    }
                }
                writes.finish(success = true)
                onOpFinished()
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            worker.post {
                if (!isCurrentSession(g) || characteristic.uuid != Gaia.CHAR_COMMAND ||
                    writes.inFlight !is GattOp.WriteCommand) return@post
                val inFlightOp = writes.inFlight as? GattOp.WriteCommand
                val isGet = inFlightOp?.isGet == true
                val success = status == BluetoothGatt.GATT_SUCCESS
                // Persist before waking a widget waiter that renders the cached mode.
                if (success && inFlightOp?.mode != null) {
                    selectionStore.saveLastKnownMode(inFlightOp.mode)
                }
                val completedMode = writes.finish(success)
                if (inFlightOp?.kind == CommandKind.BATTERY_GET) {
                    if (!success) finishBatteryRequest(false)
                    onOpFinished()
                    return@post
                }
                if (completedMode != null) {
                    if (!ready) earlySetCompleted = true
                    logLatency("mode-write-complete")
                    poll.onSetCompleted(completedMode)
                    startPollLoop()
                }
                if (isGet) {
                    if (!success) {
                        worker.removeCallbacks(getTimeoutRunnable)
                        poll.onGetSettled()
                        finishModeReads(false)
                    } else if (poll.shouldArmGetTimeout()) {
                        scheduleGetTimeout()
                    }
                    onOpFinished()
                    return@post
                }
                if (!success) {
                    if (!ready) beginDisconnect()
                    setError("connect failed")
                    return@post
                }
                hopSession {
                    _state.update { current ->
                        current.copy(
                            currentMode = completedMode ?: current.currentMode,
                            lastKnownMode = completedMode ?: current.lastKnownMode,
                            status = if (current.status == "sending") {
                                if (ready) "connected" else "connecting"
                            } else current.status,
                        )
                    }
                }
                onOpFinished()
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            worker.post { if (isCurrentSession(g)) handleRx(value) }
        }

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                val value = characteristic.value ?: return
                worker.post { if (isCurrentSession(g)) handleRx(value) }
            }
        }
    }

    companion object {
        private const val SCAN_TIMEOUT_MS = 12000L
        private const val CONNECT_TIMEOUT_MS = 20000L
        private const val WRITE_GAP_MS = 100L
        private const val CLOSE_SETTLE_MS = 300L
        private const val DISCONNECT_TIMEOUT_MS = 2000L
        private const val POLL_INTERVAL_MS = 2000L
        private const val GET_RESPONSE_TIMEOUT_MS = 1500L
        private const val BATTERY_RESPONSE_TIMEOUT_MS = 3_000L
    }
}
