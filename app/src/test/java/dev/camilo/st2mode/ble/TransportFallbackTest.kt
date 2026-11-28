package dev.camilo.st2mode.ble

import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportFallbackTest {
    @Test
    fun bleFallbackScansOnlyForInteractiveCallers() {
        assertEquals(BleFallback.CachedEndpoint, bleFallback("endpoint", allowScan = false))
        assertEquals(BleFallback.CachedEndpoint, bleFallback("endpoint", allowScan = true))
        assertEquals(BleFallback.Scan, bleFallback(null, allowScan = true))
        // A widget or refresh without a saved endpoint must not start discovery.
        assertEquals(BleFallback.NeedsApp, bleFallback(null, allowScan = false))
    }

    @Test
    fun failedWriteCarriesTheInFlightSetAndQueuedSetsInOrder() {
        val writes = ModeWrites()
        val anc = set(AncMode.Anc)
        val read = get()
        val off = set(AncMode.Off)
        writes.enqueue(anc)
        writes.enqueue(read)
        writes.enqueue(off)
        writes.startNext()

        val unsent = writes.drainUnsentSets(inFlightFailed = true)

        assertEquals(listOf(anc, off), unsent)
        // Carried commands stay pending so the next transport completes them.
        assertFalse(anc.completion!!.isCompleted)
        assertFalse(off.completion!!.isCompleted)
        assertFalse(read.completion!!.getCompleted())
        assertNull(writes.inFlight)
        assertNull(writes.startNext())
    }

    @Test
    fun setWhoseWriteMayHaveLandedIsNeverRepeated() {
        val writes = ModeWrites()
        val anc = set(AncMode.Anc)
        val off = set(AncMode.Off)
        writes.enqueue(anc)
        writes.enqueue(off)
        writes.startNext()

        // The socket closed mid-write; the earbuds may already have applied ANC.
        val unsent = writes.drainUnsentSets(inFlightFailed = false)

        assertEquals(listOf(off), unsent)
        assertFalse(anc.completion!!.getCompleted())
    }

    @Test
    fun cancelledCommandsAreNotCarried() {
        val writes = ModeWrites()
        val anc = set(AncMode.Anc)
        writes.enqueue(anc)
        anc.completion!!.cancel()

        assertTrue(writes.drainUnsentSets(inFlightFailed = true).isEmpty())
    }

    @Test
    fun carriedSetsRunFirstOnTheFallbackConnection() {
        val pending = PendingModeWrites()
        val anc = set(AncMode.Anc)
        pending.add("earbuds", attempt = 3, op = anc)

        val writes = ModeWrites()
        writes.enqueueServiceSetup(pending.take("earbuds", attempt = 3))

        assertSame(anc, writes.startNext())
    }

    private fun set(mode: AncMode) = GattOp.WriteCommand(
        Gaia.setMode(mode.setCode), sending = true, mode = mode, completion = CompletableDeferred(),
    )

    private fun get() = GattOp.WriteCommand(
        Gaia.getCurrentMode(), sending = false, completion = CompletableDeferred(),
    )
}
