package com.portal.wake.wake

import com.portal.commons.audio.OpenWakeWordDetector
import com.portal.commons.audio.VoskWakeDetector
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeRoutingTest {

    @Test fun owwAlwaysRoutes() {
        assertTrue(WakeRouting.shouldRoute(OpenWakeWordDetector.ID, "jarvis", owwActive = true, owwOwnedId = "jarvis"))
        assertTrue(WakeRouting.shouldRoute(OpenWakeWordDetector.ID, "alexa", owwActive = true, owwOwnedId = "jarvis"))
    }

    @Test fun voskRoutesNonOwnedWordsWhenOwwActive() {
        assertTrue(WakeRouting.shouldRoute(VoskWakeDetector.ID, "alexa", owwActive = true, owwOwnedId = "jarvis"))
        assertTrue(WakeRouting.shouldRoute(VoskWakeDetector.ID, "custom", owwActive = true, owwOwnedId = "jarvis"))
    }

    @Test fun voskSuppressesOwnedWordWhenOwwActive() {
        assertFalse(WakeRouting.shouldRoute(VoskWakeDetector.ID, "jarvis", owwActive = true, owwOwnedId = "jarvis"))
    }

    @Test fun voskRoutesOwnedWordWhenOwwInactive() {
        assertTrue(WakeRouting.shouldRoute(VoskWakeDetector.ID, "jarvis", owwActive = false, owwOwnedId = null))
        assertTrue(WakeRouting.shouldRoute(VoskWakeDetector.ID, "jarvis", owwActive = true, owwOwnedId = null))
    }

    @Test fun unknownDetectorNeverRoutes() {
        assertFalse(WakeRouting.shouldRoute("shadow", "jarvis", owwActive = true, owwOwnedId = "jarvis"))
    }
}
