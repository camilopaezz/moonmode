package dev.camilo.st2mode.widget

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** True when [address] is connected for A2DP audio, so its Classic link is already up. */
@SuppressLint("MissingPermission")
internal suspend fun isAudioConnected(context: Context, address: String): Boolean =
    withTimeoutOrNull(2_000L) {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: return@withTimeoutOrNull false
        awaitProfileConnection<BluetoothProfile>(
            request = { connected, disconnected ->
                adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) = connected(proxy)
                    override fun onServiceDisconnected(profile: Int) = disconnected()
                }, BluetoothProfile.A2DP)
            },
            isConnected = { proxy ->
                proxy.connectedDevices.any { it.address.equals(address, ignoreCase = true) }
            },
            close = { proxy -> adapter.closeProfileProxy(BluetoothProfile.A2DP, proxy) },
        )
    } ?: false

/** Bridges the profile's callbacks, including disconnection caused by closing our proxy. */
internal suspend fun <T> awaitProfileConnection(
    request: (onConnected: (T) -> Unit, onDisconnected: () -> Unit) -> Boolean,
    isConnected: (T) -> Boolean,
    close: (T) -> Unit,
): Boolean = suspendCancellableCoroutine { continuation ->
    val accepted = try {
        request({ proxy ->
            val connected = try {
                isConnected(proxy)
            } catch (_: SecurityException) {
                false
            }
            try {
                // Closing the proxy may synchronously call onDisconnected. Complete first.
                if (continuation.isActive) continuation.resume(connected)
            } finally {
                close(proxy)
            }
        }, {
            if (continuation.isActive) continuation.resume(false)
        })
    } catch (_: SecurityException) {
        false
    }
    if (!accepted && continuation.isActive) continuation.resume(false)
}
