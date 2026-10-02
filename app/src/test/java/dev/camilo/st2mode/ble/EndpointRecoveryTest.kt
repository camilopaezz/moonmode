package dev.camilo.st2mode.ble

import org.junit.Assert.*
import org.junit.Test

class EndpointRecoveryTest {
    @Test fun unattendedFailurePreservesTheConfirmedEndpointForTheNextAttempt() {
        val saved = mutableMapOf("earbuds" to "confirmed-endpoint")
        assertFalse(recoverCachedEndpoint("earbuds", false) { saved.remove(it) })
        assertEquals("confirmed-endpoint", cachedEndpoint("earbuds", saved::get))
    }

    @Test fun interactiveFailureForgetsOnlyTheFailingDeviceBeforeDiscovery() {
        val saved = mutableMapOf("earbuds" to "stale-endpoint", "other" to "other-endpoint")
        assertTrue(recoverCachedEndpoint("earbuds", true) { saved.remove(it) })
        assertNull(cachedEndpoint("earbuds", saved::get))
        assertEquals("other-endpoint", cachedEndpoint("other", saved::get))
    }
}
