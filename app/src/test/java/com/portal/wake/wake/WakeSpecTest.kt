package com.portal.wake.wake

import com.portal.commons.audio.WakeMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WakeSpec.build] — turns a plugin's declared meta-data fields into a validated [com.portal.commons.audio.WakeWord],
 * reporting any mistake (with the offending field) so a developer can fix it from the log. Keyword/lead
 * derivation is WakeWord.fromPhrase's job; here we cover validation + that the right reason is reported.
 */
class WakeSpecTest {

    private fun build(
        phrase: String?,
        id: String? = null,
        minConfidence: String? = null,
        onError: (String) -> Unit = {},
    ) = WakeSpec.build(phrase, id, minConfidence, WakeMatcher.BASELINE_CONF, onError)

    @Test fun buildsAndDerivesKeywordAndLead() {
        val w = build("hey jarvis", id = "jarvis", minConfidence = "0.6")!!
        assertEquals("jarvis", w.id)
        assertEquals("jarvis", w.keyword)
        assertEquals("hey", w.lead)
        assertEquals("hey jarvis", w.phrase)
        assertEquals(0.6, w.minConf, 1e-9)
    }

    @Test fun supportsAnArbitraryLead() {
        val w = build("hi bob")!!
        assertEquals("bob", w.keyword)
        assertEquals("hi", w.lead)
        assertEquals("bob", w.id) // id defaults to the keyword
    }

    @Test fun supportsASingleWordWithNoLead() {
        val w = build("computer")!!
        assertEquals("computer", w.keyword)
        assertNull(w.lead)
    }

    @Test fun lowercasesAndTrims() {
        val w = build("  Hey Jarvis  ", id = "JAR")!!
        assertEquals("hey jarvis", w.phrase)
        assertEquals("jar", w.id)
    }

    @Test fun missingPhraseIsRejectedWithReason() {
        val errs = mutableListOf<String>()
        assertNull(build(null, onError = { errs.add(it) }))
        assertNull(build("   ", onError = { errs.add(it) }))
        assertEquals(2, errs.size)
        assertTrue(errs.all { WakeContract.META_PHRASE in it })
    }

    @Test fun absentMinConfidenceUsesDefault() {
        assertEquals(WakeMatcher.BASELINE_CONF, build("hey jarvis")!!.minConf, 1e-9)
        assertEquals(WakeMatcher.BASELINE_CONF, build("hey jarvis", minConfidence = "  ")!!.minConf, 1e-9)
    }

    @Test fun acceptsBoundaryConfidences() {
        assertEquals(0.0, build("hey jarvis", minConfidence = "0.0")!!.minConf, 1e-9)
        assertEquals(1.0, build("hey jarvis", minConfidence = "1.0")!!.minConf, 1e-9)
    }

    @Test fun malformedMinConfidenceFallsBackToDefaultWithWarning() {
        // min_confidence is OPTIONAL: a non-number, an out-of-range value, or NaN/Infinity must NOT reject
        // the word — it warns and uses the default. (NaN/Infinity are excluded by the float range check.)
        for (bad in listOf("abc", "1.7", "-0.1", "NaN", "Infinity")) {
            val warnings = mutableListOf<String>()
            val w = build("hey jarvis", minConfidence = bad, onError = { warnings.add(it) })
            assertEquals("dropped on '$bad'", WakeMatcher.BASELINE_CONF, w!!.minConf, 1e-9)
            assertTrue("no warning for '$bad'", warnings.single().contains(WakeContract.META_MIN_CONFIDENCE))
        }
    }

    @Test fun phraseLongerThanTwoWordsKeepsLastTwo() {
        // Documents the single-token-lead limitation: the keyword is the last word, the lead the one before.
        val w = build("hey there jarvis")!!
        assertEquals("jarvis", w.keyword)
        assertEquals("there", w.lead)
    }

    @Test fun idNotMatchingKeywordWarnsButStillBuilds() {
        // phrase="hey vega" + id="jarvis" is almost always a typo — warn, but still honor the declared id.
        val warnings = mutableListOf<String>()
        val w = build("hey vega", id = "jarvis", onError = { warnings.add(it) })
        assertEquals("vega", w!!.keyword)
        assertEquals("jarvis", w.id)
        assertTrue(warnings.single().contains(WakeContract.META_ID))
    }

    @Test fun idMatchingKeywordDoesNotWarn() {
        val warnings = mutableListOf<String>()
        build("hey vega", id = "vega", onError = { warnings.add(it) })
        assertTrue(warnings.isEmpty())
    }
}
