package dev.camilo.st2mode.ble

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ColdModeCommandTest {
    @Test
    fun coldWidgetCommandIsWrittenBeforeEitherSubscriptionOrRead() {
        val pending = PendingModeWrites()
        val writes = ModeWrites()
        val completion = CompletableDeferred<Boolean>()
        pending.add("selected", 1, command(AncMode.Anc, completion))
        // The same discovery setup used by the GATT callback must choose SET first.
        writes.enqueueServiceSetup(pending.take("selected", 1))
        val first = writes.startNext() as GattOp.WriteCommand
        assertEquals(AncMode.Anc, first.mode)
        assertFalse(completion.isCompleted)
        writes.finish(true)
        assertTrue(runBlocking { completion.await() })
        assertEquals(GattOp.WriteCccd(Gaia.CHAR_RESPONSE), writes.startNext())
        writes.finish(true)
        assertEquals(GattOp.WriteCccd(Gaia.CHAR_DATA), writes.startNext())
        writes.finish(true)
        assertNull(writes.startNext())
    }

    @Test
    fun ordinaryDiscoveryStillStartsWithSubscriptions() {
        val writes = ModeWrites()
        writes.enqueueServiceSetup(emptyList())
        assertEquals(GattOp.WriteCccd(Gaia.CHAR_RESPONSE), writes.startNext())
        writes.finish(true)
        assertEquals(GattOp.WriteCccd(Gaia.CHAR_DATA), writes.startNext())
    }

    @Test
    fun cancellationBeforeDiscoverySkipsModeButKeepsSetup() {
        val pending = PendingModeWrites()
        val completion = CompletableDeferred<Boolean>()
        pending.add("selected", 1, command(AncMode.Transparency, completion))
        completion.cancel()
        val writes = ModeWrites()
        writes.enqueueServiceSetup(pending.take("selected", 1))
        assertEquals(GattOp.WriteCccd(Gaia.CHAR_RESPONSE), writes.startNext())
    }

    @Test
    fun pendingCommandCannotCrossSelectionOrConnectionAttempt() {
        for ((address, attempt) in listOf("other" to 1, "selected" to 2)) {
            val pending = PendingModeWrites()
            val completion = CompletableDeferred<Boolean>()
            pending.add("selected", 1, command(AncMode.Anc, completion))
            assertTrue(pending.take(address, attempt).isEmpty())
            assertFalse(runBlocking { completion.await() })
        }
    }

    @Test
    fun disconnectOrSetupFailureCompletesPendingCommandWithoutWritingIt() {
        val pending = PendingModeWrites()
        val completion = CompletableDeferred<Boolean>()
        pending.add("selected", 1, command(AncMode.Off, completion))
        pending.clear()
        assertFalse(runBlocking { completion.await() })
        assertTrue(pending.take("selected", 1).isEmpty())
    }

    @Test
    fun discoveryTransfersCommandOnlyOnce() {
        val pending = PendingModeWrites()
        pending.add("selected", 1, command(AncMode.Anc, CompletableDeferred()))
        assertEquals(1, pending.take("selected", 1).size)
        assertTrue(pending.take("selected", 1).isEmpty())
    }

    @Test
    fun commandArrivingDuringSetupOvertakesQueuedSubscriptionsButNotActiveWrite() {
        val writes = ModeWrites()
        writes.enqueueServiceSetup(emptyList())
        val active = writes.startNext()
        val completion = CompletableDeferred<Boolean>()
        writes.enqueuePriority(command(AncMode.Off, completion))
        assertEquals(active, writes.inFlight)
        assertNull(writes.startNext())
        writes.finish(true)
        assertEquals(AncMode.Off, (writes.startNext() as GattOp.WriteCommand).mode)
        writes.finish(true)
        assertTrue(runBlocking { completion.await() })
        assertEquals(GattOp.WriteCccd(Gaia.CHAR_DATA), writes.startNext())
    }

    @Test
    fun priorityCommandPreservesEarlierSetOrder() {
        val writes = ModeWrites()
        writes.enqueueServiceSetup(emptyList())
        writes.startNext()
        writes.enqueue(command(AncMode.Anc, CompletableDeferred()))
        writes.enqueuePriority(command(AncMode.Transparency, CompletableDeferred()))
        writes.finish(true)
        assertEquals(AncMode.Anc, (writes.startNext() as GattOp.WriteCommand).mode)
        writes.finish(true)
        assertEquals(AncMode.Transparency, (writes.startNext() as GattOp.WriteCommand).mode)
        writes.finish(true)
        assertEquals(GattOp.WriteCccd(Gaia.CHAR_DATA), writes.startNext())
    }

    @Test
    fun cancellationAfterDiscoverySkipsQueuedSetWithoutDroppingSubscriptions() {
        val writes = ModeWrites()
        val completion = CompletableDeferred<Boolean>()
        writes.enqueueServiceSetup(listOf(command(AncMode.Anc, completion)))
        completion.cancel()
        assertEquals(GattOp.WriteCccd(Gaia.CHAR_RESPONSE), writes.startNext())
        writes.finish(true)
        assertEquals(GattOp.WriteCccd(Gaia.CHAR_DATA), writes.startNext())
    }

    private fun command(mode: AncMode, completion: CompletableDeferred<Boolean>) =
        GattOp.WriteCommand(Gaia.setMode(mode.setCode), sending = true, mode = mode, completion = completion)
}
