package com.portal.wake.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** META_KEYWORDS wire-format parsing — shared by wake-word discovery and plugin manifest tests. */
class WakeKeywordParserTest {

    @Test fun parsesSingleSpec() {
        val w = WakeKeywordParser.parse("jarvis;hey jarvis;jarvis;0.5")
        assertEquals(1, w.size)
        assertEquals("jarvis", w[0].id)
        assertEquals("hey jarvis", w[0].phrase)
        assertEquals("jarvis", w[0].keyword)
        assertEquals(0.5, w[0].minConf, 1e-9)
    }

    @Test fun parsesMultipleSpecs() {
        val w = WakeKeywordParser.parse("alexa;hey alexa;alexa;0.5|jarvis;hey jarvis;jarvis;0.6")
        assertEquals(listOf("alexa", "jarvis"), w.map { it.id })
        assertEquals(0.6, w[1].minConf, 1e-9)
    }

    @Test fun minConfDefaultsWhenOmitted() {
        val w = WakeKeywordParser.parse("x;hey x;x")
        assertEquals(WakeKeywordParser.DEFAULT_MIN_CONF, w[0].minConf, 1e-9)
    }

    @Test fun minConfDefaultsWhenUnparseable() {
        val w = WakeKeywordParser.parse("x;hey x;x;notanumber")
        assertEquals(WakeKeywordParser.DEFAULT_MIN_CONF, w[0].minConf, 1e-9)
    }

    @Test fun lowercasesFields() {
        val w = WakeKeywordParser.parse("Jar;Hey Jarvis;JARVIS")
        assertEquals("jar", w[0].id)
        assertEquals("hey jarvis", w[0].phrase)
        assertEquals("jarvis", w[0].keyword)
    }

    @Test fun dropsMalformedSpecsButKeepsValidOnes() {
        val w = WakeKeywordParser.parse("bad;two|jarvis;hey jarvis;jarvis;0.5|")
        assertEquals(listOf("jarvis"), w.map { it.id })
    }

    @Test fun emptyStringYieldsNothing() {
        assertTrue(WakeKeywordParser.parse("").isEmpty())
    }

    @Test fun invokesOnRejectedForBadSegments() {
        val rejected = mutableListOf<String>()
        WakeKeywordParser.parse("bad;two|ok;hey ok;ok", onRejected = { rejected.add(it) })
        assertEquals(listOf("bad;two"), rejected)
    }

    @Test fun clampsMinConfIntoUnitRange() {
        val w = WakeKeywordParser.parse("hi;hey hi;hi;5|lo;hey lo;lo;-2")
        assertEquals(1.0, w[0].minConf, 1e-9)
        assertEquals(0.0, w[1].minConf, 1e-9)
    }

    @Test fun reportsUnparseableMinConfButKeepsSpecWithDefault() {
        val rejected = mutableListOf<String>()
        val w = WakeKeywordParser.parse("x;hey x;x;notanumber", onRejected = { rejected.add(it) })
        assertEquals(1, w.size)
        assertEquals(WakeKeywordParser.DEFAULT_MIN_CONF, w[0].minConf, 1e-9)
        assertEquals(listOf("x;hey x;x;notanumber"), rejected)
    }

    @Test fun emptyMinConfFieldDefaultsSilently() {
        val rejected = mutableListOf<String>()
        val w = WakeKeywordParser.parse("x;hey x;x;", onRejected = { rejected.add(it) })
        assertEquals(WakeKeywordParser.DEFAULT_MIN_CONF, w[0].minConf, 1e-9)
        assertTrue(rejected.isEmpty())
    }
}
