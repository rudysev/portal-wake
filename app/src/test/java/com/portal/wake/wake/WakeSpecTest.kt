package com.portal.wake.wake

import com.portal.commons.audio.TwoStageTuning
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
        scoreThreshold: String? = null,
        onError: (String) -> Unit = {},
    ) = WakeSpec.build(
        phrase = phrase,
        id = id,
        minConfidence = minConfidence,
        defaultMinConf = WakeMatcher.BASELINE_CONF,
        scoreThreshold = scoreThreshold,
        onProblem = onError,
    )

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

    // ---- score_threshold: the openWakeWord stage-1 gate ---------------------------------------------

    @Test fun defaultsToTheLooseStageOneThreshold() {
        // Loose on purpose: stage 1 is a recall filter, and the Vosk verify stage is the precision gate.
        assertEquals(TwoStageTuning.DEFAULT_STAGE1_THRESHOLD, build("hey jarvis")!!.scoreThreshold, 1e-9)
    }

    @Test fun honoursADeclaredScoreThreshold() {
        assertEquals(0.7, build("hey jarvis", scoreThreshold = "0.7")!!.scoreThreshold, 1e-9)
    }

    @Test fun scoreThresholdIsIndependentOfMinConfidence() {
        // The two are different scales — a Vosk per-word confidence vs an openWakeWord probability — so
        // declaring one must never move the other.
        val w = build("hey jarvis", minConfidence = "0.6", scoreThreshold = "0.4")!!
        assertEquals(0.6, w.minConf, 1e-9)
        assertEquals(0.4, w.scoreThreshold, 1e-9)
    }

    @Test fun minConfidenceAloneLeavesStageOneAtItsDefault() {
        // Every plugin written before openWakeWord existed declares only min_confidence. It must keep
        // meaning the Vosk floor, and must not silently tighten stage 1 to a value on the wrong scale.
        val w = build("hey jarvis", minConfidence = "0.55")!!
        assertEquals(0.55, w.minConf, 1e-9)
        assertEquals(TwoStageTuning.DEFAULT_STAGE1_THRESHOLD, w.scoreThreshold, 1e-9)
    }

    @Test fun malformedScoreThresholdIsReportedAgainstItsOwnFieldAndDefaults() {
        val warnings = mutableListOf<String>()
        val w = build("hey jarvis", scoreThreshold = "abc", onError = { warnings.add(it) })
        assertEquals(TwoStageTuning.DEFAULT_STAGE1_THRESHOLD, w!!.scoreThreshold, 1e-9)
        assertTrue(warnings.single().contains(WakeContract.META_SCORE_THRESHOLD))
    }

    @Test fun outOfRangeScoreThresholdDefaults() {
        assertEquals(TwoStageTuning.DEFAULT_STAGE1_THRESHOLD, build("hey jarvis", scoreThreshold = "1.5")!!.scoreThreshold, 1e-9)
        assertEquals(TwoStageTuning.DEFAULT_STAGE1_THRESHOLD, build("hey jarvis", scoreThreshold = "-0.1")!!.scoreThreshold, 1e-9)
    }
}
