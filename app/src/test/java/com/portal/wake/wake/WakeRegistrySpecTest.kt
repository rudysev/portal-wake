package com.portal.wake.wake

import com.portal.commons.audio.WakeWord
import org.junit.Assert.assertEquals
import org.junit.Test

/** Locks the built-in "jarvis" default the registry falls back to when no plugin declares it. */
class WakeRegistrySpecTest {

    @Test fun builtinJarvisHasHeyLeadAndDefaultThreshold() {
        val j = WakeRegistry.BUILTIN_JARVIS
        assertEquals("jarvis", j.id)
        assertEquals("jarvis", j.keyword)
        assertEquals("hey", j.lead)
        assertEquals("hey jarvis", j.phrase)
        assertEquals(WakeWord.DEFAULT_SCORE_THRESHOLD, j.scoreThreshold, 1e-9)
    }
}
