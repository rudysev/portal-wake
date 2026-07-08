package com.portal.wake.wake

import com.portal.commons.audio.OpenWakeWordDetector
import com.portal.commons.audio.VoskWakeDetector
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the pure routing decision for the "openWakeWord is the default" split: oww routes the word it owns,
 * Vosk routes everything else and is the fallback for that word only when oww is inactive, and shadows never
 * route. The double-fire guard (oww active → Vosk's fire of the owned word is suppressed) is the crux.
 */
class WakeRoutingTest {

    private val oww = OpenWakeWordDetector.NAME
    private val vosk = VoskWakeDetector.NAME

    @Test fun owwAlwaysRoutes() {
        assertTrue(WakeRouting.shouldRoute(oww, "jarvis", owwActive = true, owwOwnedId = "jarvis"))
    }

    @Test fun voskRoutesTheOwnedWord_whenOwwInactive() {
        // No oww (assets absent) → Vosk is the sole detector and must route jarvis (the fallback).
        assertTrue(WakeRouting.shouldRoute(vosk, "jarvis", owwActive = false, owwOwnedId = null))
    }

    @Test fun voskIsSuppressedForTheOwnedWord_whenOwwActive() {
        // oww owns jarvis → Vosk's jarvis fire is a shadow (else the same utterance hands off twice).
        assertFalse(WakeRouting.shouldRoute(vosk, "jarvis", owwActive = true, owwOwnedId = "jarvis"))
    }

    @Test fun voskRoutesOtherWords_evenWhenOwwActive() {
        // alexa + any plugin word oww can't hear still route via Vosk.
        assertTrue(WakeRouting.shouldRoute(vosk, "alexa", owwActive = true, owwOwnedId = "jarvis"))
        assertTrue(WakeRouting.shouldRoute(vosk, "computer", owwActive = true, owwOwnedId = "jarvis"))
    }

    @Test fun voskSuppressionFollowsARemappedOwnedId() {
        // If oww owns a phrase remapped to a non-"jarvis" id, Vosk must be suppressed on THAT id — not a
        // hard-coded "jarvis" — so a re-id can't reintroduce the double-fire.
        assertFalse(WakeRouting.shouldRoute(vosk, "myassistant", owwActive = true, owwOwnedId = "myassistant"))
        assertTrue(WakeRouting.shouldRoute(vosk, "jarvis", owwActive = true, owwOwnedId = "myassistant"))
    }

    @Test fun voskRoutesTheOwnedWord_whenOwwActiveButOwnsNothing() {
        // oww active but the set has no jarvis word (owwOwnedId null) → nothing to suppress; Vosk routes all.
        assertTrue(WakeRouting.shouldRoute(vosk, "jarvis", owwActive = true, owwOwnedId = null))
    }

    @Test fun shadowDetectorNeverRoutes() {
        assertFalse(WakeRouting.shouldRoute("mww", "jarvis", owwActive = true, owwOwnedId = "jarvis"))
        assertFalse(WakeRouting.shouldRoute("mww", "jarvis", owwActive = false, owwOwnedId = null))
    }
}
