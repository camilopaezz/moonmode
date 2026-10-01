package dev.camilo.st2mode.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointMatchingTest {
    @Test
    fun matchesSelectedModelWithoutDependingOnTheAudioAddress() {
        assertTrue(matchesEndpointName("Space Travel 2", "Space Travel 2"))
        assertTrue(matchesEndpointName("Space Travel 2", "SPACE TRAVEL 2"))
    }

    @Test
    fun presentsEveryDistinctMatchingAddressForExplicitSelection() {
        val choices = endpointChoices(
            "Space Travel 2",
            listOf(
                BleEndpoint("02", "SPACE TRAVEL 2"),
                BleEndpoint("01", "Space Travel 2"),
                BleEndpoint("02", "Space Travel 2"),
                BleEndpoint("03", "Other Earbuds"),
            ),
        )
        assertTrue(choices.map { it.address } == listOf("01", "02"))
    }

    @Test
    fun confirmedEndpointIsScopedToTheBondedAudioAddress() {
        val saved = mapOf("audio-one" to "ble-one", "audio-two" to "ble-two")
        assertEquals("ble-one", cachedEndpoint("audio-one", saved::get))
        assertEquals("ble-two", cachedEndpoint("audio-two", saved::get))
        assertNull(cachedEndpoint("audio-three", saved::get))
        assertNull(cachedEndpoint(null, saved::get))
    }

    @Test
    fun invalidatingOneEndpointDoesNotAffectAnotherBondedDevice() {
        val saved = mutableMapOf("audio-one" to "ble-one", "audio-two" to "ble-two")
        saved.remove("audio-one")
        assertNull(cachedEndpoint("audio-one", saved::get))
        assertEquals("ble-two", cachedEndpoint("audio-two", saved::get))
    }

    @Test
    fun ignoresUnrelatedAndUnnamedAdvertisements() {
        assertFalse(matchesEndpointName("Space Travel 2", "Space Travel"))
        assertFalse(matchesEndpointName("Space Travel 2", "Other Earbuds"))
        assertFalse(matchesEndpointName("Space Travel 2", null))
        assertFalse(matchesEndpointName(null, "Space Travel 2"))
    }
}
