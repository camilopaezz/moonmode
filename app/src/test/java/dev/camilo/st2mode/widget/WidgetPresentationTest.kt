package dev.camilo.st2mode.widget

import dev.camilo.st2mode.ble.AncMode
import dev.camilo.st2mode.ble.BatteryLevels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WidgetPresentationTest {
    @Test
    fun batteryCaptionHidesUnknownAndDisconnectedBuds() {
        assertEquals("L 80% · R 70%", widgetBatteryText(BatteryLevels(80, 70, 1000), 1000))
        assertEquals("R 70%", widgetBatteryText(BatteryLevels(0, 70, 1000), 1000))
        assertEquals("L 80%", widgetBatteryText(BatteryLevels(80, 0, 1000), 1000))
        assertNull(widgetBatteryText(BatteryLevels(0, 0, 1000), 1000))
        assertEquals("R 70%", widgetBatteryText(BatteryLevels(null, 70, 1000), 1000))
        assertNull(widgetBatteryText(BatteryLevels(null, null, 1000), 1000))
        assertNull(widgetBatteryText(null, 1000))
    }

    @Test
    fun oldOrFutureReadingsAreHidden() {
        val reading = BatteryLevels(80, 70, 1000)
        assertEquals("L 80% · R 70%", widgetBatteryText(reading, 1000 + BatteryLevels.MAX_AGE_MS))
        assertNull(widgetBatteryText(reading, 1001 + BatteryLevels.MAX_AGE_MS))
        assertNull(widgetBatteryText(reading, 999))
    }

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
