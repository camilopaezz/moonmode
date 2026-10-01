package dev.camilo.st2mode.ui

import dev.camilo.st2mode.ble.AncMode
import dev.camilo.st2mode.ble.ClientState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModePresentationTest {
    @Test
    fun initialStateIsUnknownNotOff() {
        val view = modePresentation(ClientState())
        assertNull(view.displayedMode)
        assertNull(view.liveMode)
        assertFalse(view.isLive)
        assertFalse(view.controlsEnabled)
    }

    @Test
    fun disconnectedStateUsesOnlyExplicitCacheEvenWithStaleCurrentMode() {
        val view = modePresentation(ClientState(currentMode = AncMode.Off, lastKnownMode = AncMode.Anc))
        assertEquals(AncMode.Anc, view.displayedMode)
        assertNull(view.liveMode)
        assertFalse(view.isLive)
        assertFalse(view.controlsEnabled)
    }

    @Test
    fun disconnectedStateWithoutCacheDoesNotShowStaleMode() {
        val view = modePresentation(ClientState(currentMode = AncMode.Off))
        assertNull(view.displayedMode)
        assertNull(view.liveMode)
    }

    @Test
    fun setupAndErrorStatesNeverSelectACachedMode() {
        for (status in listOf("scanning", "connecting", "select endpoint", "error")) {
            val view = modePresentation(ClientState(
                status = status, currentMode = AncMode.Anc, lastKnownMode = AncMode.Transparency,
            ))
            assertEquals(AncMode.Transparency, view.displayedMode)
            assertNull(view.liveMode)
            assertFalse(view.controlsEnabled)
        }
    }

    @Test
    fun readyStateUsesTheRealReadingRatherThanTheCache() {
        val view = modePresentation(ClientState(
            status = "connected", ready = true,
            currentMode = AncMode.Transparency, lastKnownMode = AncMode.Anc,
        ))
        assertEquals(AncMode.Transparency, view.displayedMode)
        assertEquals(AncMode.Transparency, view.liveMode)
        assertTrue(view.isLive)
        assertTrue(view.controlsEnabled)
    }

    @Test
    fun readyWithoutAReadingKeepsCacheExplicitlyNotLive() {
        val cached = modePresentation(ClientState(ready = true, lastKnownMode = AncMode.Anc))
        assertEquals(AncMode.Anc, cached.displayedMode)
        assertNull(cached.liveMode)
        assertFalse(cached.isLive)
        val empty = modePresentation(ClientState(ready = true))
        assertNull(empty.displayedMode)
        assertNull(empty.liveMode)
        assertTrue(empty.controlsEnabled)
    }

    @Test
    fun sendingLeavesTheConfirmedModeInPlaceUntilTheCallbackUpdatesIt() {
        val initial = ClientState(
            status = "sending", ready = true,
            currentMode = AncMode.Off, lastKnownMode = AncMode.Off,
        )
        val pending = modePresentation(initial)
        assertEquals(AncMode.Off, pending.liveMode)
        assertTrue(pending.changing)
        assertFalse(pending.controlsEnabled)
        val confirmed = modePresentation(initial.copy(
            status = "connected", currentMode = AncMode.Anc, lastKnownMode = AncMode.Anc,
        ))
        assertEquals(AncMode.Anc, confirmed.liveMode)
        assertFalse(confirmed.changing)
        assertTrue(confirmed.controlsEnabled)
    }
}
