package com.portal.wake.wake

import com.portal.wake.audio.WakeMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the built-in "jarvis" default the registry falls back to when no plugin declares it: it must keep
 * the strict floor (lenient clean-phrase bypass OFF) and the "hey" lead, so a low-confidence or lead-less
 * "jarvis" can't fire from the fallback route. The installed assistant overrides it via its manifest, but
 * the default must match. (Spec parsing/validation is covered by [WakeSpecTest].)
 */
class WakeRegistrySpecTest {

    @Test fun builtinJarvisIsStrictWithHeyLead() {
        val j = WakeRegistry.BUILTIN_JARVIS
        assertEquals("jarvis", j.id)
        assertEquals("jarvis", j.keyword)
        assertEquals("hey", j.lead)
        assertEquals("hey jarvis", j.phrase)
        assertEquals(WakeMatcher.STRICT_MIN_CONF, j.minConf, 1e-9)
        assertTrue(j.minConf > WakeMatcher.BASELINE_CONF)
    }
}
