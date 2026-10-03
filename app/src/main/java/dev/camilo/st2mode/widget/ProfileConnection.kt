package dev.camilo.st2mode.widget

import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

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
