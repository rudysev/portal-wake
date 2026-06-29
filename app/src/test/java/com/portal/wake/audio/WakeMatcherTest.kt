package com.portal.wake.audio

import com.portal.wake.audio.WakeMatcher.RecWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Accuracy policy tests — the heart of "fire on a real wake, don't miss one, don't false-trigger".
 * [WakeMatcher] is a pure function, so every precision/recall trade-off is pinned down here.
 */
class WakeMatcherTest {

    // vega is lenient (floor at the baseline). "jarvis" stands in for a strict plugin word (floor
    // above the baseline) so we still cover the no-lenient-bypass path.
    private val vega = WakeWord("vega", keyword = "vega", lead = "hey", minConf = WakeMatcher.BASELINE_CONF)
    private val jarvis = WakeWord("jarvis", keyword = "jarvis", lead = "hey", minConf = 0.60)
    private val words = listOf(vega, jarvis)

    private fun rec(vararg pairs: Pair<String, Double>) = pairs.map { RecWord(it.first, it.second) }

    // ---- recall: don't miss a genuine wake ---------------------------------------------------------

    @Test fun cleanPhrase_highConfidence_matches() {
        assertEquals("vega", WakeMatcher.match(rec("hey" to 0.99, "vega" to 0.98), words))
    }

    @Test fun cleanPhrase_lowConfidence_stillMatches_forLenientWord() {
        // The model can under-rate the exact phrase at distance; a clean short phrase still fires.
        assertEquals("vega", WakeMatcher.match(rec("hey" to 0.20, "vega" to 0.10), words))
    }

    @Test fun heyVariants_areAccepted() {
        for (hey in listOf("hey", "hay")) {
            assertEquals("matched on '$hey'", "vega", WakeMatcher.match(rec(hey to 0.5, "vega" to 0.1), words))
        }
    }

    @Test fun looseHeyWords_areRejected() {
        // "a", "hi", and "he" were too loose a lead-in (common filler / mis-decodes), so they no longer
        // count as the mandatory "hey" — a keyword preceded only by them must not fire.
        for (notHey in listOf("a", "hi", "he")) {
            assertNull("fired on '$notHey'", WakeMatcher.match(rec(notHey to 0.9, "vega" to 0.1), words))
        }
    }

    @Test fun keywordMentionedEarlier_thenRealWake_matches() {
        // "did you ask vega? … oh wait, hey vega" — the genuine wake is the LAST "vega" (preceded
        // by "hey"); the earlier incidental "vega" (no preceding hey) must not pre-empt it.
        val transcript = rec(
            "did" to 0.9,
            "you" to 0.9,
            "ask" to 0.9,
            "vega" to 0.9,
            "oh" to 0.9,
            "wait" to 0.9,
            "hey" to 0.9,
            "vega" to 0.95,
        )
        assertEquals("vega", WakeMatcher.match(transcript, words))
    }

    @Test fun caseInsensitive() {
        assertEquals("vega", WakeMatcher.match(rec("HEY" to 0.9, "Vega" to 0.9), words))
    }

    @Test fun embeddedKeyword_highConfidence_matches() {
        // Longer than a clean phrase, but the keyword clearly cleared its floor → accept.
        val r = rec("hey" to 0.9, "could" to 0.8, "you" to 0.8, "ask" to 0.8, "vega" to 0.95)
        assertEquals("vega", WakeMatcher.match(r, words))
    }

    // ---- precision: don't fire on non-wakes --------------------------------------------------------

    @Test fun keywordWithoutHey_doesNotMatch() {
        assertNull(WakeMatcher.match(rec("vega" to 0.99), words))
        assertNull(WakeMatcher.match(rec("tell" to 0.9, "vega" to 0.99), words))
    }

    @Test fun lookAlikeWord_doesNotMatch() {
        assertNull(WakeMatcher.match(rec("hey" to 0.9, "jeremy" to 0.9), words))
    }

    @Test fun embeddedKeyword_lowConfidence_doesNotMatch() {
        // Keyword buried in a long utterance and under its floor → reject (incidental mention).
        val r = rec("hey" to 0.9, "could" to 0.8, "you" to 0.8, "ask" to 0.8, "vega" to 0.15)
        assertNull(WakeMatcher.match(r, words))
    }

    @Test fun emptyInput_doesNotMatch() {
        assertNull(WakeMatcher.match(emptyList(), words))
    }

    // ---- per-word strictness: a hand-off route demands real confidence -----------------------------

    @Test fun strictWord_cleanPhraseButLowConfidence_doesNotMatch() {
        // jarvis floor is 0.60 and is NOT lenient — a clean phrase alone is not enough.
        assertNull(WakeMatcher.match(rec("hey" to 0.9, "jarvis" to 0.40), words))
    }

    @Test fun strictWord_aboveFloor_matches() {
        assertEquals("jarvis", WakeMatcher.match(rec("hey" to 0.9, "jarvis" to 0.75), words))
    }

    @Test fun strictWord_longBackgroundDecode_doesNotMatch() {
        // The observed live-stream FP: a 6-word [unk]-laden decode with "hey jarvis" at the tail and the
        // keyword over its floor (0.83 ≥ 0.60). A strict route must reject it — it isn't a clean phrase.
        val r = rec("foo" to 1.0, "bar" to 0.98, "baz" to 0.99, "qux" to 0.9, "hey" to 0.84, "jarvis" to 0.83)
        assertNull(WakeMatcher.match(r, words))
    }

    @Test fun strictWord_crossContaminatedDecode_doesNotMatch() {
        // A *clean* 3-word decode that still carries a rival wake keyword ("alexa" while matching jarvis) is
        // suspect — a genuine "hey jarvis" never also contains "alexa". Reject regardless of length/confidence.
        val both = listOf(jarvis, WakeWord("alexa", keyword = "alexa", lead = "hey", minConf = 0.60))
        assertNull(WakeMatcher.match(rec("alexa" to 0.79, "hey" to 0.84, "jarvis" to 0.83), both))
    }

    @Test fun strictWord_cleanThreeWordPhrase_stillMatches() {
        // Guard against over-blocking: a real strict wake with a stray lead-in word (3 words, no rival
        // keyword, keyword over floor) must still fire.
        assertEquals("jarvis", WakeMatcher.match(rec("um" to 0.5, "hey" to 0.9, "jarvis" to 0.83), words))
    }

    @Test fun strictWord_embeddedHighConfidence_doesNotMatch() {
        // The accepted trade-off, pinned: an embedded strict wake (5 words) does NOT fire even at 0.95 —
        // unlike the lenient `embeddedKeyword_highConfidence_matches` (vega), which does.
        val r = rec("hey" to 0.9, "could" to 0.8, "you" to 0.8, "ask" to 0.8, "jarvis" to 0.95)
        assertNull(WakeMatcher.match(r, words))
    }

    @Test fun strictWord_exactProductionFalseFire_doesNotMatch() {
        // The real live-stream FP verbatim, with the production registry (jarvis + alexa both strict): a
        // 6-word [unk]-laden decode carrying "alexa" and "hey jarvis" (0.83). Both gates fail it.
        val prod = listOf(jarvis, WakeWord("alexa", keyword = "alexa", lead = "hey", minConf = 0.60))
        val r = rec("[unk]" to 1.0, "alexa" to 0.79, "[unk]" to 0.98, "[unk]" to 0.99, "hey" to 0.84, "jarvis" to 0.83)
        assertNull(WakeMatcher.match(r, prod))
    }

    @Test fun strictWord_weakHey_doesNotMatch() {
        // A short clean "hey jarvis", jarvis over its floor (0.95) but "hey" under-confident at 0.66 — the
        // signature of background audio. A real wake decodes "hey" at 0.96–1.00, so the LEAD_MIN_CONF floor
        // rejects this with no recall cost. (No "[unk]" here, so the contamination gate isn't what's tested.)
        assertNull(WakeMatcher.match(rec("hey" to 0.66, "jarvis" to 0.95), words))
    }

    @Test fun strictWord_confidentHey_matches() {
        // A genuine bare wake — no "[unk]", confident "hey" (≥ LEAD_MIN_CONF), keyword over floor — must fire.
        assertEquals("jarvis", WakeMatcher.match(rec("hey" to 0.96, "jarvis" to 0.95), words))
    }

    @Test fun strictWord_contaminatedDecode_doesNotMatch() {
        // The live FP verbatim: `[unk](51) hey(99) jarvis(99)` — a 3-word "clean" phrase with BOTH words maxed,
        // so no confidence floor could ever catch it. The leading "[unk]" is background audio captured in the
        // same window; a genuine close-mic wake never decodes one, so a strict route rejects any "[unk]".
        assertNull(WakeMatcher.match(rec("[unk]" to 0.51, "hey" to 0.99, "jarvis" to 0.99), words))
    }

    @Test fun lenientWord_weakHey_stillMatches() {
        // The hey-confidence floor is a STRICT-route guard only. A lenient word's clean short phrase still
        // fires on a weak "hey" (recall insurance) — the strict FP fix must not regress lenient recall.
        assertEquals("vega", WakeMatcher.match(rec("hey" to 0.66, "vega" to 0.95), words))
    }

    @Test fun noPerWordConfidence_lenientStillFires_strictDoesNot() {
        // conf == NO_CONF (-1): the lenient clean-phrase path can fire; the strict floor cannot.
        assertEquals("vega", WakeMatcher.match(rec("hey" to -1.0, "vega" to -1.0), words))
        assertNull(WakeMatcher.match(rec("hey" to -1.0, "jarvis" to -1.0), words))
    }

    // ---- multi-wake routing ------------------------------------------------------------------------

    @Test fun routesToTheSpokenWord() {
        assertEquals("jarvis", WakeMatcher.match(rec("hey" to 0.9, "jarvis" to 0.9), words))
        assertEquals("vega", WakeMatcher.match(rec("hey" to 0.9, "vega" to 0.9), words))
    }

    @Test fun extensible_customWakeWord() {
        val computer = WakeWord("computer", keyword = "computer", lead = "hey", minConf = 0.55)
        assertEquals("computer", WakeMatcher.match(rec("hey" to 0.9, "computer" to 0.9), listOf(computer)))
    }

    @Test fun pluginDeclaredLead_hiBob_firesOnHiNotHey() {
        // The lead comes from the plugin's declared phrase, not a hardcoded "hey": "hi bob" requires "hi".
        val bob = WakeWord("bob", keyword = "bob", lead = "hi", minConf = 0.55)
        assertEquals("bob", WakeMatcher.match(rec("hi" to 0.9, "bob" to 0.9), listOf(bob)))
        assertNull(WakeMatcher.match(rec("hey" to 0.9, "bob" to 0.9), listOf(bob)))
    }

    @Test fun noLeadWord_firesOnBareKeyword() {
        // A single-word phrase declares no lead → no prefix gate; a clean bare keyword fires.
        val computer = WakeWord("computer", keyword = "computer", lead = null, minConf = 0.55)
        assertEquals("computer", WakeMatcher.match(rec("computer" to 0.9), listOf(computer)))
    }

    // ---- evaluate(): near-miss diagnostics ---------------------------------------------------------
    // A wake keyword decoded but a gate rejected it → NearMiss carrying the failing gate's reason.

    @Test fun evaluate_match_mirrorsMatch() {
        assertEquals(
            WakeMatcher.Outcome.Match("jarvis"),
            WakeMatcher.evaluate(rec("hey" to 0.9, "jarvis" to 0.9), words),
        )
    }

    @Test fun evaluate_noKeyword_isNone() {
        // Ambient speech with no registered keyword isn't worth logging.
        assertEquals(WakeMatcher.Outcome.None, WakeMatcher.evaluate(rec("hello" to 0.9, "there" to 0.9), words))
        assertEquals(WakeMatcher.Outcome.None, WakeMatcher.evaluate(emptyList(), words))
    }

    @Test fun evaluate_keywordWithoutHey_isNearMiss() {
        val out = WakeMatcher.evaluate(rec("tell" to 0.9, "jarvis" to 0.99), words)
        assertTrue(out is WakeMatcher.Outcome.NearMiss && out.keyword == "jarvis" && "no 'hey'" in out.reason)
    }

    @Test fun evaluate_weakHey_isNearMiss() {
        // The live FP class: short clean "hey jarvis" with an under-confident "hey".
        val out = WakeMatcher.evaluate(rec("hey" to 0.66, "jarvis" to 0.95), words)
        assertTrue(out is WakeMatcher.Outcome.NearMiss && "'hey' under" in out.reason)
    }

    @Test fun evaluate_lowKeywordConf_isNearMiss() {
        val out = WakeMatcher.evaluate(rec("hey" to 0.9, "jarvis" to 0.40), words)
        assertTrue(out is WakeMatcher.Outcome.NearMiss && "'jarvis'" in out.reason && "floor" in out.reason)
    }

    @Test fun evaluate_longPhrase_isNearMiss() {
        // Four real words, no "[unk]", so the length gate (not the contamination gate) is what fires.
        val out = WakeMatcher.evaluate(rec("um" to 0.5, "hey" to 0.96, "jarvis" to 0.95, "weather" to 0.9), words)
        assertTrue(out is WakeMatcher.Outcome.NearMiss && "phrase too long" in out.reason)
    }

    @Test fun evaluate_contaminated_isNearMiss() {
        // The live FP `[unk](51) hey(99) jarvis(99)` surfaces as a near-miss naming the contamination gate.
        val out = WakeMatcher.evaluate(rec("[unk]" to 0.51, "hey" to 0.99, "jarvis" to 0.99), words)
        assertTrue(out is WakeMatcher.Outcome.NearMiss && "contaminated" in out.reason)
    }
}
