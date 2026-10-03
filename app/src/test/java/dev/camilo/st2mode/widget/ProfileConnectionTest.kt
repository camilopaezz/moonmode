package dev.camilo.st2mode.widget

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileConnectionTest {
    @Test
    fun closingConnectedProxyCannotOverwriteConnectedResult() = runBlocking {
        lateinit var disconnected: () -> Unit
        var closed = false
        val result = awaitProfileConnection(
            request = { connected, onDisconnected ->
                disconnected = onDisconnected
                connected(Unit)
                true
            },
            isConnected = { true },
            close = {
                closed = true
                disconnected()
            },
        )
        assertTrue("Selected earbuds are connected even when proxy cleanup fires onDisconnected", result)
        assertTrue(closed)
    }

    @Test
    fun closesProxyWhenSelectedDeviceIsAbsent() = runBlocking {
        var closed = false
        val result = awaitProfileConnection(
            request = { connected, _ -> connected(Unit); true },
            isConnected = { false },
            close = { closed = true },
        )
        assertFalse(result)
        assertTrue(closed)
    }

    @Test
    fun deniedProfileRequestReturnsDisconnected() = runBlocking {
        val result = awaitProfileConnection<Unit>(
            request = { _, _ -> throw SecurityException("Permission revoked") },
            isConnected = { error("No proxy") },
            close = { error("No proxy") },
        )
        assertFalse(result)
    }

    @Test
    fun proxyArrivingAfterCancellationStillCloses() = runBlocking {
        lateinit var connected: (Unit) -> Unit
        var closed = false
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            awaitProfileConnection<Unit>(
                request = { onConnected, _ -> connected = onConnected; true },
                isConnected = { true },
                close = { closed = true },
            )
        }
        job.cancelAndJoin()
        connected(Unit)
        assertTrue(closed)
    }
}
