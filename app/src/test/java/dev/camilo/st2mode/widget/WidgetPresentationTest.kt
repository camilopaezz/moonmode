package dev.camilo.st2mode.widget

import dev.camilo.st2mode.ble.AncMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WidgetPresentationTest {
    @Test
    fun emptyCacheHasNoSelectedStopEvenWhileACommandIsPending() {
        assertNull(WidgetPresentation(null, null).selectedMode)
        assertNull(WidgetPresentation(null, "Changing to Off…").selectedMode)
    }

    @Test
    fun eachSupportedCachedModeSelectsItsOwnStop() {
        for (mode in listOf(AncMode.Off, AncMode.Anc, AncMode.Transparency)) {
            assertEquals(mode, WidgetPresentation(mode, null).selectedMode)
        }
    }

    @Test
    fun pendingAndFailedCommandsDoNotSelectTheirRequestedMode() {
        for (status in listOf("Changing to Transparency…", "Couldn't change mode. Open app")) {
            val view = WidgetPresentation(AncMode.Anc, status)
            assertEquals(AncMode.Anc, view.selectedMode)
            assertEquals(AncMode.Anc, view.lastKnownMode)
            assertEquals(status, view.status)
        }
    }

    @Test
    fun unsupportedCacheDoesNotInventAnAdditionalControlOrSelectOff() {
        val view = WidgetPresentation(AncMode.Wind, null)
        assertEquals(AncMode.Wind, view.lastKnownMode)
        assertNull(view.selectedMode)
    }
}
