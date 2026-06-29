package com.portal.wake.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the Vosk-result → [WakeMatcher.RecWord] mapping (the boundary between the decoder and the
 * pure matcher). Uses real `org.json` on the JVM (see `testOptions` in build.gradle.kts).
 */
class WakeRecognizerParseTest {

    @Test fun parsesPerWordResultWithConfidence() {
        val json = """
            {"result":[
              {"conf":0.97,"word":"hey"},
              {"conf":0.81,"word":"jarvis"}
            ],"text":"hey jarvis"}
        """.trimIndent()
        val words = WakeRecognizer.parseResult(json)
        assertEquals(listOf("hey", "jarvis"), words.map { it.word })
        assertEquals(0.97, words[0].conf, 1e-9)
        assertEquals(0.81, words[1].conf, 1e-9)
    }

    @Test fun fallsBackToTextWhenNoPerWordData() {
        val words = WakeRecognizer.parseResult("""{"text":"hey jarvis"}""")
        assertEquals(listOf("hey", "jarvis"), words.map { it.word })
        // Unknown confidence → the NO_CONF sentinel (only the lenient path may fire).
        assertTrue(words.all { it.conf == WakeRecognizer.NO_CONF })
    }

    @Test fun emptyResultsYieldNoWords() {
        assertTrue(WakeRecognizer.parseResult("""{"text":""}""").isEmpty())
        assertTrue(WakeRecognizer.parseResult("""{}""").isEmpty())
    }

    @Test fun malformedJsonYieldsNoWords() {
        assertTrue(WakeRecognizer.parseResult("not json").isEmpty())
    }

    @Test fun endToEnd_parseThenMatch() {
        val jarvis = WakeWord.fromPhrase("hey jarvis", id = "jarvis", minConf = WakeMatcher.BASELINE_CONF)!!
        val json = """{"result":[{"conf":0.9,"word":"hey"},{"conf":0.2,"word":"jarvis"}],"text":"hey jarvis"}"""
        val id = WakeMatcher.match(WakeRecognizer.parseResult(json), listOf(jarvis))
        assertEquals("jarvis", id)
    }
}
