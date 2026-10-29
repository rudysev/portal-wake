package com.portal.wake.wake

import com.portal.commons.audio.WakeMatcher
import com.portal.commons.audio.WakeWord

/**
 * A wake plugin's wake declaration: builds a [WakeWord] from its declared meta-data fields (see
 * [WakeContract]). It reads the contract's keys itself through a key-to-text reader; reading each value off the
 * manifest `Bundle` is the caller's job ([WakeRegistry]).
 *
 * Every declaration problem is reported through [build]'s `onProblem` with the **exact field**, so a
 * developer sees e.g. `'com.portal.wake.min_confidence' is not a number in 0.0..1.0: "abc" — using
 * default 0.5` in `debug.txt` rather than a silent no-op. Keyword/lead derivation lives in
 * [WakeWord.fromPhrase] (one owner); this only validates and delegates. Pure Kotlin/JVM (no Android),
 * fully unit-tested.
 */
object WakeDeclaration {

    /**
     * Read the declared fields through [read] (a [WakeContract] meta-data key → its text, null when absent),
     * validate them and build a [WakeWord], reporting any problem via [onProblem]:
     *  - [WakeContract.META_PHRASE] missing/blank → **rejected** (returns null); it's the one required field;
     *  - [WakeContract.META_MIN_CONFIDENCE] absent/blank, or present but not a number in
     *    [[WakeContract.MIN_CONFIDENCE], [WakeContract.MAX_CONFIDENCE]] → falls back to
     *    [WakeMatcher.BASELINE_CONF]. A malformed value is reported (loud) but does **not** reject the word — the
     *    field is optional, so a typo here shouldn't disable an otherwise-valid wake word;
     *  - [WakeContract.META_ID] absent/blank → the keyword (handled in [WakeWord.fromPhrase]); present but not
     *    the derived keyword → reported (likely a typo), the word still builds with that id;
     *  - a phrase of more than two words, or a word stricter than [WakeMatcher.BASELINE_CONF] with no lead →
     *    reported, the word still builds.
     */
    fun build(read: (key: String) -> String?, onProblem: (reason: String) -> Unit): WakeWord? {
        val phrase = read(WakeContract.META_PHRASE).orEmpty()
        val id = read(WakeContract.META_ID)
        // A blank phrase is rejected before min_confidence is read, so a missing phrase is its only problem.
        val word = if (phrase.isBlank()) {
            null
        } else {
            WakeWord.fromPhrase(phrase, id, minConfOrDefault(read(WakeContract.META_MIN_CONFIDENCE), onProblem))
        }
        if (word == null) {
            onProblem("missing required '${WakeContract.META_PHRASE}'")
            return null
        }
        // A provided id that isn't the derived keyword is usually a typo (e.g. phrase="hey vega",
        // id="jarvis"). It still builds (an id may legitimately differ), but flag it — the id is what's
        // reported on a match and what overrides a built-in default.
        if (!id.isNullOrBlank() && word.id != word.keyword) {
            onProblem(
                "'${WakeContract.META_ID}' \"${word.id}\" is not the keyword \"${word.keyword}\" derived from " +
                    "'${WakeContract.META_PHRASE}' — is that intended?",
            )
        }
        warnIfSurprising(phrase, word, onProblem)
        return word
    }

    /**
     * Report the declaration mistakes that *build* a word but make it behave surprisingly: a phrase longer than
     * two words (only the last two are kept, so the rest never reach the grammar), and a word stricter than
     * [WakeMatcher.BASELINE_CONF] with no lead (it fires on the bare keyword).
     */
    private fun warnIfSurprising(declaredPhrase: String, w: WakeWord, onProblem: (reason: String) -> Unit) {
        val wordCount = WakeWord.tokenize(declaredPhrase).size
        if (wordCount > 2) {
            onProblem(
                "'${WakeContract.META_PHRASE}' \"$declaredPhrase\" has $wordCount words — " +
                    "only the last two are used (lead \"${w.lead}\" + keyword \"${w.keyword}\"); " +
                    "declare a two-word phrase",
            )
        }
        if (w.lead == null && w.minConf > WakeMatcher.BASELINE_CONF) {
            onProblem(
                "wake word '${w.keyword}' is strict ('${WakeContract.META_MIN_CONFIDENCE}' ${w.minConf}) but " +
                    "declares no lead — it will fire on the bare keyword; declare a lead in " +
                    "'${WakeContract.META_PHRASE}' (e.g. \"hey ${w.keyword}\")",
            )
        }
    }

    /**
     * The declared [minConfidence], or [WakeMatcher.BASELINE_CONF] when it is absent, or reported via [onProblem]
     * when invalid.
     */
    private fun minConfOrDefault(minConfidence: String?, onProblem: (reason: String) -> Unit): Double {
        val defaultMinConf = WakeMatcher.BASELINE_CONF
        val n = minConfidence?.trim()?.toDoubleOrNull()
        return when {
            minConfidence.isNullOrBlank() -> defaultMinConf

            // `in` a floating-point range also excludes NaN (in no range) and Infinity, not just ordinary
            // out-of-bounds values, so a degenerate confidence can't slip through.
            n != null && n in WakeContract.MIN_CONFIDENCE..WakeContract.MAX_CONFIDENCE -> n

            else -> {
                onProblem(
                    "'${WakeContract.META_MIN_CONFIDENCE}' is not a number in " +
                        "${WakeContract.MIN_CONFIDENCE}..${WakeContract.MAX_CONFIDENCE}: " +
                        "\"$minConfidence\" — using default $defaultMinConf",
                )
                defaultMinConf
            }
        }
    }
}
