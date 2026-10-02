package dev.camilo.st2mode.ble

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModeStateTest {
    @Test
    fun backgroundSingleReadAcceptsStateWithoutEnablingContinuousPolling() {
        val poll = ModePoll { 0L }
        assertFalse(poll.enabled)
        assertFalse(poll.shouldEnqueueGet(ready = true, setBusy = false))
        poll.onGetEnqueued()
        assertTrue(poll.acceptGetReport(AncMode.Transparency))
        assertFalse(poll.awaitingGet)
        assertFalse(poll.enabled)
        assertFalse(poll.shouldEnqueueGet(ready = true, setBusy = false))
    }

    @Test
    fun pollImmediatelyAfterSetCannotRevertTheDisplayedMode() {
        val poll = ModePoll { 0L }
        val writes = ModeWrites()
        poll.setEnabled(true)
        poll.onSetRequested()
        writes.enqueue(command(AncMode.Anc))
        writes.startNext()
        var displayed = writes.finish(success = true)
        poll.onSetCompleted(requireNotNull(displayed))
        // A GATT write acknowledgement can precede the earbuds applying the mode.
        // Replay a poll tick in that gap, with a reading of the previous mode.
        if (poll.shouldEnqueueGet(ready = true, setBusy = writes.hasSet())) {
            poll.onGetEnqueued()
            val oldReading = decodeModeReport(capturedGet(0x00))
            if (poll.acceptGetReport(requireNotNull(oldReading))) displayed = oldReading
        }
        assertEquals("A settling earbud must not flicker back to Off", AncMode.Anc, displayed)
    }

    @Test
    fun everyCompletedSetRestartsTheQuietPeriod() {
        var now = 0L
        val poll = ModePoll { now }
        poll.setEnabled(true)
        poll.onSetCompleted(AncMode.Anc)
        now = 1_500
        poll.onSetCompleted(AncMode.Transparency)
        now = 3_499
        assertFalse(poll.shouldEnqueueGet(ready = true, setBusy = false))
        now = 3_500
        assertTrue(poll.shouldEnqueueGet(ready = true, setBusy = false))
    }

    @Test
    fun rapidSwitchingIgnoresLaggingReadingsUntilLatestModeIsStable() {
        var now = 0L
        val poll = ModePoll { now }
        poll.setEnabled(true)
        poll.onSetRequested()
        poll.onSetCompleted(AncMode.Anc)
        now = 500
        poll.onSetRequested()
        poll.onSetCompleted(AncMode.Off)
        now = 1_000
        poll.onSetRequested()
        poll.onSetCompleted(AncMode.Anc)
        now = 3_000
        fun report(mode: AncMode): Boolean {
            assertTrue(poll.shouldEnqueueGet(ready = true, setBusy = false))
            poll.onGetEnqueued()
            return poll.acceptGetReport(mode)
        }
        assertTrue(report(AncMode.Anc))
        now = 5_000
        // This is the middle Off in A -> B -> A, arriving after a matching A.
        assertFalse(report(AncMode.Off))
        now = 7_000
        assertTrue(report(AncMode.Anc))
        now = 9_000
        assertTrue(report(AncMode.Anc))
        // Once settled, a real change from the earbuds is authoritative again.
        assertTrue(report(AncMode.Transparency))
    }

    @Test
    fun confirmationTimeoutReconcilesToActualModeWhenDeviceDidNotApplySet() {
        var now = 0L
        val poll = ModePoll { now }
        poll.onSetCompleted(AncMode.Anc)
        now = 9_999
        poll.onGetEnqueued()
        assertFalse(poll.acceptGetReport(AncMode.Off))
        now = 10_000
        poll.onGetEnqueued()
        assertTrue(poll.acceptGetReport(AncMode.Off))
    }

    @Test
    fun disconnectClearsPendingConfirmationAndQuietPeriod() {
        val poll = ModePoll { 0L }
        poll.setEnabled(true)
        poll.onSetCompleted(AncMode.Anc)
        poll.resetOutstanding()
        assertTrue(poll.shouldEnqueueGet(ready = true, setBusy = false))
        poll.onGetEnqueued()
        assertTrue(poll.acceptGetReport(AncMode.Off))
    }

    @Test
    fun capturedGetResponseMapsOffAncTransparency() {
        assertArrayEquals(hex("001d110300010000"), capturedGet(0x00))
        assertEquals(AncMode.Off, decodeModeReport(hex("001d110300010000")))
        assertEquals(AncMode.Anc, decodeModeReport(hex("001d110301010000")))
        assertEquals(AncMode.Transparency, decodeModeReport(hex("001d110302010000")))
        assertEquals(AncMode.Off, AncMode.fromGetPayload(0))
        assertEquals(AncMode.Anc, AncMode.fromGetPayload(1))
        assertEquals(AncMode.Transparency, AncMode.fromGetPayload(2))
    }

    @Test
    fun getNotificationUsesTheSameStrictMapping() {
        assertEquals(
            AncMode.Off,
            decodeModeReport(Gaia.frame(Gaia.GET_CURRENT_MODE_NOTIFICATION, byteArrayOf(0x00, 0x01, 0x00, 0x00))),
        )
        assertEquals(
            AncMode.Anc,
            decodeModeReport(Gaia.frame(Gaia.GET_CURRENT_MODE_NOTIFICATION, byteArrayOf(0x01, 0x01, 0x00, 0x00))),
        )
        assertEquals(
            AncMode.Transparency,
            decodeModeReport(Gaia.frame(Gaia.GET_CURRENT_MODE_NOTIFICATION, byteArrayOf(0x02, 0x01, 0x00, 0x00))),
        )
    }

    @Test
    fun unknownAndTruncatedGetResponsesAreIgnored() {
        assertNull(decodeModeReport(hex("001d110303010000")))
        assertNull(decodeModeReport(hex("001d110304010000")))
        assertNull(decodeModeReport(hex("001d1103ff010000")))
        assertNull(AncMode.fromGetPayload(3))
        assertNull(AncMode.fromGetPayload(4))
        assertNull(decodeModeReport(hex("001d1103")))
        assertNull(decodeModeReport(hex("001d11")))
        assertNull(decodeModeReport(byteArrayOf()))
        assertNull(decodeModeReport(Gaia.frame(Gaia.GET_CURRENT_MODE_RESPONSE)))
    }

    @Test
    fun setResponseIsIgnoredAndSetCodesStaySeparateFromGet() {
        assertFalse(Gaia.isModeReportRx(Gaia.SET_MODE_RESPONSE))
        assertFalse(Gaia.isModeReportRx(Gaia.SET_MODE_NOTIFICATION))
        assertTrue(Gaia.isModeReportRx(Gaia.GET_CURRENT_MODE_RESPONSE))
        assertTrue(Gaia.isModeReportRx(Gaia.GET_CURRENT_MODE_NOTIFICATION))
        assertNull(decodeModeReport(Gaia.frame(Gaia.SET_MODE_RESPONSE, byteArrayOf(0))))
        assertNull(decodeModeReport(Gaia.frame(Gaia.SET_MODE_RESPONSE, byteArrayOf(1))))
        assertNull(decodeModeReport(Gaia.frame(Gaia.SET_MODE_NOTIFICATION, byteArrayOf(0))))
        assertEquals(1, AncMode.Off.setCode)
        assertEquals(2, AncMode.Anc.setCode)
        assertEquals(4, AncMode.Transparency.setCode)
        assertArrayEquals(byteArrayOf(0x00, 0x1D, 0x10, 0x04, 0x01), Gaia.setMode(AncMode.Off.setCode))
        assertArrayEquals(byteArrayOf(0x00, 0x1D, 0x10, 0x04, 0x02), Gaia.setMode(AncMode.Anc.setCode))
        assertArrayEquals(byteArrayOf(0x00, 0x1D, 0x10, 0x04, 0x04), Gaia.setMode(AncMode.Transparency.setCode))
        assertEquals(AncMode.Anc, AncMode.fromGetPayload(AncMode.Off.setCode))
        assertEquals(AncMode.Transparency, AncMode.fromGetPayload(AncMode.Anc.setCode))
        assertNull(AncMode.fromGetPayload(AncMode.Transparency.setCode))
    }

    @Test
    fun reportsRemainAuthoritativeAfterASuccessfulWrite() {
        val writes = ModeWrites()
        writes.enqueue(command(AncMode.Anc))
        writes.startNext()
        assertEquals(AncMode.Anc, writes.finish(success = true))
        assertEquals(AncMode.Transparency, decodeModeReport(hex("001d110302010000")))
        assertNull(decodeModeReport(Gaia.frame(Gaia.SET_MODE_RESPONSE, byteArrayOf(0))))
    }

    @Test
    fun enqueuingOrStartingAWriteDoesNotSelectItsMode() {
        val writes = ModeWrites()
        val anc = command(AncMode.Anc)
        writes.enqueue(anc)
        assertNull(writes.inFlight)
        assertEquals(anc, writes.startNext())
        assertEquals(anc, writes.inFlight)
    }

    @Test
    fun successfulWriteSelectsOnlyTheInFlightMode() {
        val writes = ModeWrites()
        val anc = command(AncMode.Anc)
        val transparency = command(AncMode.Transparency)
        writes.enqueue(anc)
        writes.enqueue(transparency)
        assertEquals(anc, writes.startNext())
        assertEquals(AncMode.Anc, writes.finish(success = true))
        assertEquals(transparency, writes.startNext())
        assertEquals(AncMode.Transparency, writes.finish(success = true))
        assertNull(writes.startNext())
    }

    @Test
    fun failedWriteAndTeardownNeverSelectQueuedMode() {
        val writes = ModeWrites()
        writes.enqueue(command(AncMode.Anc))
        writes.enqueue(command(AncMode.Transparency))
        writes.startNext()
        assertNull(writes.finish(success = false))
        assertNull(writes.startNext())
        writes.enqueue(command(AncMode.Off))
        writes.startNext()
        writes.clear()
        assertNull(writes.finish(success = true))
        assertNull(writes.inFlight)
    }

    @Test
    fun widgetWaiterCompletesOnlyForItsOwnWrite() = runBlocking {
        val writes = ModeWrites()
        val first = CompletableDeferred<Boolean>()
        val second = CompletableDeferred<Boolean>()
        writes.enqueue(command(AncMode.Anc).copy(completion = first))
        writes.enqueue(command(AncMode.Transparency).copy(completion = second))
        writes.startNext()
        assertFalse(first.isCompleted)
        writes.finish(success = true)
        assertTrue(first.await())
        assertFalse(second.isCompleted)
        writes.startNext()
        writes.finish(success = true)
        assertTrue(second.await())
    }

    @Test
    fun failedWriteAndTeardownReleaseAllWidgetWaiters() = runBlocking {
        val writes = ModeWrites()
        val first = CompletableDeferred<Boolean>()
        val second = CompletableDeferred<Boolean>()
        writes.enqueue(command(AncMode.Anc).copy(completion = first))
        writes.enqueue(command(AncMode.Transparency).copy(completion = second))
        writes.startNext()
        writes.finish(success = false)
        assertFalse(first.await())
        assertFalse(second.await())
        val active = CompletableDeferred<Boolean>()
        val queued = CompletableDeferred<Boolean>()
        writes.enqueue(command(AncMode.Off).copy(completion = active))
        writes.enqueue(command(AncMode.Anc).copy(completion = queued))
        writes.startNext()
        writes.clear()
        assertFalse(active.await())
        assertFalse(queued.await())
    }

    @Test
    fun timedOutQueuedCommandIsSkipped() {
        val writes = ModeWrites()
        val expired = CompletableDeferred<Boolean>()
        writes.enqueue(command(AncMode.Anc).copy(completion = expired))
        writes.enqueue(command(AncMode.Off))
        expired.cancel()
        assertEquals(AncMode.Off, (writes.startNext() as GattOp.WriteCommand).mode)
        writes.finish(success = true)
        assertNull(writes.startNext())
    }

    @Test
    fun pollEnqueuesGetOnlyWhenEnabledReadyIdleAndNotAwaiting() {
        val poll = ModePoll()
        assertFalse(poll.shouldEnqueueGet(ready = true, setBusy = false))
        poll.setEnabled(true)
        assertFalse(poll.shouldEnqueueGet(ready = false, setBusy = false))
        assertFalse(poll.shouldEnqueueGet(ready = true, setBusy = true))
        assertTrue(poll.shouldEnqueueGet(ready = true, setBusy = false))
        poll.onGetEnqueued()
        assertFalse(poll.shouldEnqueueGet(ready = true, setBusy = false))
    }

    @Test
    fun getTimeoutReleasesSlotWithoutSelectingAMode() {
        val poll = ModePoll()
        poll.setEnabled(true)
        poll.onGetEnqueued()
        assertFalse(poll.shouldEnqueueGet(ready = true, setBusy = false))
        poll.onGetSettled()
        assertFalse(poll.awaitingGet)
        assertTrue(poll.shouldEnqueueGet(ready = true, setBusy = false))
    }

    @Test
    fun setInvalidatesOutstandingGetSoStaleReportDoesNotWin() {
        val poll = ModePoll()
        poll.setEnabled(true)
        poll.onGetEnqueued()
        val generationBeforeSet = poll.generation
        poll.onSetRequested()
        assertTrue(poll.generation > generationBeforeSet)
        assertTrue(poll.awaitingGet)
        assertFalse(poll.acceptGetReport(AncMode.Anc))
        assertFalse(poll.awaitingGet)
        assertTrue(poll.shouldEnqueueGet(ready = true, setBusy = false))
    }

    @Test
    fun getResponseBeforeWriteCallbackDoesNotArmTimeoutAgainstANewerGet() {
        val poll = ModePoll()
        poll.setEnabled(true)
        poll.onGetEnqueued()
        assertEquals(AncMode.Off, decodeModeReport(hex("001d110300010000")))
        assertTrue(poll.acceptGetReport(AncMode.Anc))
        assertFalse(poll.awaitingGet)
        assertFalse(poll.shouldArmGetTimeout())
        assertTrue(poll.shouldEnqueueGet(ready = true, setBusy = false))
        poll.onGetEnqueued()
        assertTrue(poll.awaitingGet)
        assertTrue(poll.shouldArmGetTimeout())
        assertFalse(poll.shouldEnqueueGet(ready = true, setBusy = false))
        assertEquals(AncMode.Anc, decodeModeReport(hex("001d110301010000")))
        assertTrue(poll.acceptGetReport(AncMode.Anc))
    }

    @Test
    fun acceptedGetReportSettlesAndMatchesCapturedFrames() {
        val poll = ModePoll()
        poll.setEnabled(true)
        poll.onGetEnqueued()
        assertEquals(AncMode.Anc, decodeModeReport(hex("001d110301010000")))
        assertTrue(poll.acceptGetReport(AncMode.Anc))
        assertFalse(poll.awaitingGet)
        assertFalse(poll.acceptGetReport(AncMode.Anc))
        assertTrue(poll.shouldEnqueueGet(ready = true, setBusy = false))
    }

    @Test
    fun queuedGetIsDroppedSoSetStaysFirstAndGetFailureKeepsSet() {
        val writes = ModeWrites()
        val get = getCommand()
        val set = command(AncMode.Transparency)
        writes.enqueue(get)
        assertFalse(writes.hasSet())
        assertTrue(writes.dropQueuedGets())
        writes.enqueue(set)
        assertTrue(writes.hasSet())
        assertEquals(set, writes.startNext())
        assertTrue(writes.hasSet())
        writes.finish(success = true)

        writes.enqueue(getCommand())
        writes.enqueue(command(AncMode.Anc))
        assertTrue(writes.hasSet())
        writes.startNext()
        assertNull(writes.finish(success = false))
        assertEquals(AncMode.Anc, (writes.startNext() as GattOp.WriteCommand).mode)
    }

    @Test
    fun droppingAQueuedGetFreesThePollSlotForLater() {
        val poll = ModePoll()
        val writes = ModeWrites()
        poll.setEnabled(true)
        writes.enqueue(getCommand())
        poll.onGetEnqueued()
        poll.onSetRequested()
        assertTrue(writes.dropQueuedGets())
        poll.onQueuedGetDropped()
        assertFalse(poll.awaitingGet)
        writes.enqueue(command(AncMode.Off))
        assertTrue(writes.hasSet())
        assertFalse(poll.shouldEnqueueGet(ready = true, setBusy = writes.hasSet()))
        writes.startNext()
        writes.finish(success = true)
        assertTrue(poll.shouldEnqueueGet(ready = true, setBusy = writes.hasSet()))
    }

    @Test
    fun resetOutstandingCancelsAwaitingGetLikeDisconnectEpoch() {
        val poll = ModePoll()
        poll.setEnabled(true)
        poll.onGetEnqueued()
        val generation = poll.generation
        poll.resetOutstanding()
        assertTrue(poll.generation > generation)
        assertFalse(poll.awaitingGet)
        assertFalse(poll.acceptGetReport(AncMode.Anc))
        assertTrue(poll.enabled)
        assertTrue(poll.shouldEnqueueGet(ready = true, setBusy = false))
    }

    @Test
    fun pauseStopsEnqueueEvenWhenReady() {
        val poll = ModePoll()
        poll.setEnabled(true)
        assertTrue(poll.shouldEnqueueGet(ready = true, setBusy = false))
        poll.setEnabled(false)
        assertFalse(poll.shouldEnqueueGet(ready = true, setBusy = false))
    }

    private fun command(mode: AncMode) =
        GattOp.WriteCommand(Gaia.setMode(mode.setCode), sending = true, mode = mode)

    private fun getCommand() =
        GattOp.WriteCommand(Gaia.getCurrentMode(), sending = false)

    private fun capturedGet(modeByte: Int): ByteArray =
        Gaia.frame(Gaia.GET_CURRENT_MODE_RESPONSE, byteArrayOf(modeByte.toByte(), 0x01, 0x00, 0x00))

    private fun hex(s: String): ByteArray {
        require(s.length % 2 == 0)
        return ByteArray(s.length / 2) { i ->
            s.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
