package com.portal.wake.wake

import com.portal.commons.audio.WakeMatcher
import com.portal.commons.audio.WakeWord
import com.portal.wake.wake.WakeContract.META_ID
import com.portal.wake.wake.WakeContract.META_MIN_CONFIDENCE
import com.portal.wake.wake.WakeContract.META_PHRASE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WakeDeclaration.build] — turns a plugin's wake declaration (its meta-data fields) into a validated
 * [com.portal.commons.audio.WakeWord], reporting any mistake (with the offending field) so a developer can fix it
 * from the log. Keyword/lead derivation is WakeWord.fromPhrase's job; here we cover validation + that the right
 * reason is reported.
 */
class WakeDeclarationTest {

    /** Build the [declaration] (meta-data key → value; a field is left out by omitting its key). */
    private fun build(declaration: Map<String, String>, onProblem: (String) -> Unit = {}): WakeWord? =
        WakeDeclaration.build(declaration::get, onProblem)

    @Test fun buildsAndDerivesKeywordAndLead() {
        val w = build(mapOf(META_PHRASE to "hey jarvis", META_ID to "jarvis", META_MIN_CONFIDENCE to "0.6"))!!
        assertEquals("jarvis", w.id)
        assertEquals("jarvis", w.keyword)
        assertEquals("hey", w.lead)
        assertEquals("hey jarvis", w.phrase)
        assertEquals(0.6, w.minConf, 1e-9)
    }

    @Test fun supportsAnArbitraryLead() {
        val w = build(mapOf(META_PHRASE to "hi bob"))!!
        assertEquals("bob", w.keyword)
        assertEquals("hi", w.lead)
        assertEquals("bob", w.id) // id defaults to the keyword
    }

    @Test fun supportsASingleWordWithNoLead() {
        val w = build(mapOf(META_PHRASE to "computer"))!!
        assertEquals("computer", w.keyword)
        assertNull(w.lead)
    }

    @Test fun lowercasesAndTrims() {
        val w = build(mapOf(META_PHRASE to "  Hey Jarvis  ", META_ID to "JAR"))!!
        assertEquals("hey jarvis", w.phrase)
        assertEquals("jar", w.id)
    }

    @Test fun missingPhraseIsRejectedWithReason() {
        val problems = mutableListOf<String>()
        assertNull(build(emptyMap(), onProblem = { problems.add(it) }))
        assertNull(build(mapOf(META_PHRASE to "   "), onProblem = { problems.add(it) }))
        assertEquals(2, problems.size)
        assertTrue(problems.all { META_PHRASE in it })
    }

    @Test fun absentMinConfidenceUsesDefault() {
        assertEquals(WakeMatcher.BASELINE_CONF, build(mapOf(META_PHRASE to "hey jarvis"))!!.minConf, 1e-9)
        assertEquals(
            WakeMatcher.BASELINE_CONF,
            build(mapOf(META_PHRASE to "hey jarvis", META_MIN_CONFIDENCE to "  "))!!.minConf,
            1e-9,
        )
    }

    @Test fun acceptsBoundaryConfidences() {
        assertEquals(0.0, build(mapOf(META_PHRASE to "hey jarvis", META_MIN_CONFIDENCE to "0.0"))!!.minConf, 1e-9)
        assertEquals(1.0, build(mapOf(META_PHRASE to "hey jarvis", META_MIN_CONFIDENCE to "1.0"))!!.minConf, 1e-9)
    }

    @Test fun malformedMinConfidenceFallsBackToDefaultWithWarning() {
        // min_confidence is OPTIONAL: a non-number, an out-of-range value, or NaN/Infinity must NOT reject
        // the word — it warns and uses the default. (NaN/Infinity are excluded by the float range check.)
        for (bad in listOf("abc", "1.7", "-0.1", "NaN", "Infinity")) {
            val problems = mutableListOf<String>()
            val w = build(
                mapOf(META_PHRASE to "hey jarvis", META_MIN_CONFIDENCE to bad),
                onProblem = { problems.add(it) },
            )
            assertEquals("dropped on '$bad'", WakeMatcher.BASELINE_CONF, w!!.minConf, 1e-9)
            assertTrue("no warning for '$bad'", problems.single().contains(META_MIN_CONFIDENCE))
        }
    }

    @Test fun phraseLongerThanTwoWordsKeepsLastTwoWithWarning() {
        // Documents the single-token-lead limitation: the keyword is the last word, the lead the one before.
        val problems = mutableListOf<String>()
        val w = build(mapOf(META_PHRASE to "hey there jarvis"), onProblem = { problems.add(it) })!!
        assertEquals("jarvis", w.keyword)
        assertEquals("there", w.lead)
        val problem = problems.single()
        assertTrue(problem, "'$META_PHRASE'" in problem)
        assertTrue(problem, "only the last two" in problem)
    }

    @Test fun strictWordWithNoLeadWarns() {
        val problems = mutableListOf<String>()
        val w = build(
            mapOf(META_PHRASE to "computer", META_MIN_CONFIDENCE to "0.8"),
            onProblem = { problems.add(it) },
        )!!
        assertEquals(0.8, w.minConf, 1e-9)
        val problem = problems.single()
        assertTrue(problem, "'$META_MIN_CONFIDENCE'" in problem)
        assertTrue(problem, "'$META_PHRASE'" in problem)
        assertTrue(problem, "no lead" in problem)
    }

    @Test fun strictWordWithALeadDoesNotWarn() {
        val problems = mutableListOf<String>()
        build(mapOf(META_PHRASE to "hey computer", META_MIN_CONFIDENCE to "0.8"), onProblem = { problems.add(it) })
        assertTrue(problems.isEmpty())
    }

    @Test fun baselineWordWithNoLeadDoesNotWarn() {
        val problems = mutableListOf<String>()
        build(
            mapOf(META_PHRASE to "computer", META_MIN_CONFIDENCE to WakeMatcher.BASELINE_CONF.toString()),
            onProblem = { problems.add(it) },
        )
        build(mapOf(META_PHRASE to "computer"), onProblem = { problems.add(it) })
        assertTrue(problems.isEmpty())
    }

    @Test fun correctDeclarationReportsNothing() {
        val problems = mutableListOf<String>()
        val w = build(
            mapOf(META_PHRASE to "hey jarvis", META_ID to "jarvis", META_MIN_CONFIDENCE to "0.7"),
            onProblem = { problems.add(it) },
        )
        assertEquals("jarvis", w!!.id)
        assertTrue(problems.isEmpty())
    }

    @Test fun reportsEachOfSeveralMistakes() {
        val problems = mutableListOf<String>()
        val w = build(
            mapOf(META_PHRASE to "hey there vega", META_ID to "jarvis", META_MIN_CONFIDENCE to "abc"),
            onProblem = { problems.add(it) },
        )
        assertEquals("vega", w!!.keyword)
        assertEquals(3, problems.size)
        assertTrue(problems.any { META_MIN_CONFIDENCE in it })
        assertTrue(problems.any { META_ID in it })
        assertTrue(problems.any { "only the last two" in it })
    }

    @Test fun idNotMatchingKeywordWarnsButStillBuilds() {
        // phrase="hey vega" + id="jarvis" is almost always a typo — warn, but still honor the declared id.
        val problems = mutableListOf<String>()
        val w = build(mapOf(META_PHRASE to "hey vega", META_ID to "jarvis"), onProblem = { problems.add(it) })
        assertEquals("vega", w!!.keyword)
        assertEquals("jarvis", w.id)
        assertTrue(problems.single().contains(META_ID))
    }

    @Test fun idMatchingKeywordDoesNotWarn() {
        val problems = mutableListOf<String>()
        build(mapOf(META_PHRASE to "hey vega", META_ID to "vega"), onProblem = { problems.add(it) })
        assertTrue(problems.isEmpty())
    }
}
