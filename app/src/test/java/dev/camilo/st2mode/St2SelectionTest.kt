package dev.camilo.st2mode

import dev.camilo.st2mode.ble.AncMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class St2SelectionTest {
    @Test
    fun restoreAcceptsOnlyAStillBondedAddress() {
        val bonded = listOf("AA:BB:CC:DD:EE:01", "AA:BB:CC:DD:EE:02")
        assertEquals("AA:BB:CC:DD:EE:01", restoreBondedSelection("AA:BB:CC:DD:EE:01", bonded))
        assertEquals("AA:BB:CC:DD:EE:02", restoreBondedSelection("aa:bb:cc:dd:ee:02", bonded))
        assertNull(restoreBondedSelection("AA:BB:CC:DD:EE:99", bonded))
        assertNull(restoreBondedSelection(null, bonded))
        assertNull(restoreBondedSelection("", bonded))
        assertNull(restoreBondedSelection("AA:BB:CC:DD:EE:01", emptyList()))
    }

    @Test
    fun resolvePrefersInMemoryWhenStillBondedElseSaved() {
        val bonded = listOf("11", "22")
        assertEquals("22", resolveBondedSelection("22", "11", bonded))
        assertEquals("11", resolveBondedSelection("99", "11", bonded))
        assertEquals("11", resolveBondedSelection(null, "11", bonded))
        assertNull(resolveBondedSelection("99", "88", bonded))
        assertNull(resolveBondedSelection(null, null, bonded))
    }

    @Test
    fun lastKnownModeRoundTripsKnownEnumsAndRejectsJunk() {
        for (mode in AncMode.entries) {
            assertEquals(mode, decodeLastKnownMode(encodeLastKnownMode(mode)))
        }
        assertEquals(AncMode.Off, decodeLastKnownMode("Off"))
        assertEquals(AncMode.Anc, decodeLastKnownMode("Anc"))
        assertEquals(AncMode.Transparency, decodeLastKnownMode("Transparency"))
        assertNull(decodeLastKnownMode(null))
        assertNull(decodeLastKnownMode(""))
        assertNull(decodeLastKnownMode("ANC"))
        assertNull(decodeLastKnownMode("0"))
        assertNull(decodeLastKnownMode("Transparency "))
    }

    @Test
    fun widgetTargetRequiresBondAndConfirmedEndpoint() {
        val bonded = listOf("audio-1", "audio-2")
        val endpoints = mapOf("audio-1" to "ble-1")
        assertEquals(
            St2WidgetTarget("audio-1", "ble-1"),
            widgetTargetOrNull("audio-1", bonded, endpoints::get),
        )
        assertEquals(
            St2WidgetTarget("audio-1", "ble-1"),
            widgetTargetOrNull("AUDIO-1", bonded, endpoints::get),
        )
        assertNull(widgetTargetOrNull("audio-2", bonded, endpoints::get))
        assertNull(widgetTargetOrNull("audio-1", bonded) { "" })
        assertNull(widgetTargetOrNull("audio-9", bonded, endpoints::get))
        assertNull(widgetTargetOrNull(null, bonded, endpoints::get))
    }

    @Test
    fun widgetCommandsAcceptOnlyTheThreeSupportedModes() {
        assertEquals(AncMode.Off, decodeWidgetMode("Off"))
        assertEquals(AncMode.Anc, decodeWidgetMode("Anc"))
        assertEquals(AncMode.Transparency, decodeWidgetMode("Transparency"))
        assertNull(decodeWidgetMode("Wind"))
        assertNull(decodeWidgetMode("ANC"))
        assertNull(decodeWidgetMode(null))
        assertNull(decodeWidgetMode(""))
    }

    @Test
    fun widgetModesExcludeWind() {
        assertEquals(listOf(AncMode.Off, AncMode.Anc, AncMode.Transparency), widgetModes())
        assertTrue(AncMode.Wind !in widgetModes())
    }
}
