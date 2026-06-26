package com.portal.wake.wake

import com.portal.wake.audio.WakeMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the plugin spec parser — the contract by which any app declares "hey X" at runtime
 * (`id;phrase;keyword[;minConf]`, multiple separated by `|`). A malformed plugin spec must never
 * break discovery, so bad entries are dropped, not thrown.
 */
class WakeRegistrySpecTest {

    @Test fun parsesSingleSpec() {
        val w = WakeRegistry.parseSpecs("jarvis;hey jarvis;jarvis;0.5")
        assertEquals(1, w.size)
        assertEquals("jarvis", w[0].id)
        assertEquals("hey jarvis", w[0].phrase)
        assertEquals("jarvis", w[0].keyword)
        assertEquals(0.5, w[0].minConf, 1e-9)
    }

    @Test fun parsesMultipleSpecs() {
        val w = WakeRegistry.parseSpecs("alexa;hey alexa;alexa;0.5|jarvis;hey jarvis;jarvis;0.6")
        assertEquals(listOf("alexa", "jarvis"), w.map { it.id })
        assertEquals(0.6, w[1].minConf, 1e-9)
    }

    @Test fun minConfDefaultsToBaselineWhenOmitted() {
        val w = WakeRegistry.parseSpecs("x;hey x;x")
        assertEquals(1, w.size)
        assertEquals(WakeMatcher.BASELINE_CONF, w[0].minConf, 1e-9)
    }

    @Test fun minConfDefaultsWhenUnparseable() {
        val w = WakeRegistry.parseSpecs("x;hey x;x;notanumber")
        assertEquals(WakeMatcher.BASELINE_CONF, w[0].minConf, 1e-9)
    }

    @Test fun lowercasesFields() {
        val w = WakeRegistry.parseSpecs("Jar;Hey Jarvis;JARVIS")
        assertEquals("jar", w[0].id)
        assertEquals("hey jarvis", w[0].phrase)
        assertEquals("jarvis", w[0].keyword)
    }

    @Test fun dropsMalformedSpecsButKeepsValidOnes() {
        // "bad;two" has too few fields; the empty segment is ignored; the valid one survives.
        val w = WakeRegistry.parseSpecs("bad;two|jarvis;hey jarvis;jarvis;0.5|")
        assertEquals(listOf("jarvis"), w.map { it.id })
    }

    @Test fun emptyStringYieldsNothing() {
        assertTrue(WakeRegistry.parseSpecs("").isEmpty())
    }

    @Test fun builtinJarvisUsesTheStrictFloor() {
        // Locks in the built-in default: the lenient clean-phrase bypass must stay OFF for jarvis (floor
        // above BASELINE_CONF), so a low-confidence short "hey jarvis" can't fire from the fallback route.
        assertEquals("jarvis", WakeRegistry.BUILTIN_JARVIS.id)
        assertEquals("hey jarvis", WakeRegistry.BUILTIN_JARVIS.phrase)
        assertEquals("jarvis", WakeRegistry.BUILTIN_JARVIS.keyword)
        assertEquals(WakeMatcher.STRICT_MIN_CONF, WakeRegistry.BUILTIN_JARVIS.minConf, 1e-9)
        assertTrue(WakeRegistry.BUILTIN_JARVIS.minConf > WakeMatcher.BASELINE_CONF)
    }
}
