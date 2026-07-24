package com.portal.wake.wake

import com.portal.commons.audio.TwoStageTuning
import com.portal.commons.audio.WakeWord

/**
 * Builds a [WakeWord] from one wake plugin's declared meta-data fields (see [WakeContract]). Reading the
 * raw values off the manifest `Bundle` is the caller's job ([WakeRegistry]); this validates them and turns
 * them into a [WakeWord].
 *
 * Every declaration problem is reported through [build]'s `onProblem` with the **exact field**, so a
 * developer sees e.g. `'com.portal.wake.min_confidence' is not a number in 0.0..1.0: "abc" — using
 * default 0.5` in `debug.txt` rather than a silent no-op. Keyword/lead derivation lives in
 * [WakeWord.fromPhrase] (one owner); this only validates and delegates. Pure Kotlin/JVM (no Android),
 * fully unit-tested.
 */
object WakeSpec {

    /**
     * Validate the declared fields and build a [WakeWord], reporting any problem via [onProblem]:
     *  - [phrase] missing/blank → **rejected** (returns null); it's the one required field;
     *  - [minConfidence] / [scoreThreshold] absent/blank, or present but not a number in
     *    [[WakeContract.MIN_CONFIDENCE], [WakeContract.MAX_CONFIDENCE]] → falls back to [defaultMinConf] /
     *    [defaultScoreThreshold]. A malformed value is reported (loud) but does **not** reject the word —
     *    the field is optional, so a typo here shouldn't disable an otherwise-valid wake word;
     *  - [id] absent/blank → the keyword (handled in [WakeWord.fromPhrase]).
     */
    fun build(
        phrase: String?,
        id: String?,
        minConfidence: String?,
        defaultMinConf: Double,
        scoreThreshold: String? = null,
        defaultScoreThreshold: Double = TwoStageTuning.DEFAULT_STAGE1_THRESHOLD,
        // Last so callers can pass it as a trailing lambda, as they did before the openWakeWord fields
        // existed — adding a field must not churn every call site.
        onProblem: (reason: String) -> Unit,
    ): WakeWord? {
        if (phrase.isNullOrBlank()) {
            onProblem("missing required '${WakeContract.META_PHRASE}'")
            return null
        }
        val minConf = confidence(minConfidence, defaultMinConf, WakeContract.META_MIN_CONFIDENCE, onProblem)
        val score = confidence(scoreThreshold, defaultScoreThreshold, WakeContract.META_SCORE_THRESHOLD, onProblem)
        // phrase is non-blank (guarded above), so fromPhrase always yields a usable word.
        val word = WakeWord.fromPhrase(phrase, id, minConf, score) ?: return null
        // A provided id that isn't the derived keyword is usually a typo (e.g. phrase="hey vega",
        // id="jarvis"). It still builds (an id may legitimately differ), but flag it — the id is what's
        // reported on a match and what overrides a built-in default.
        if (!id.isNullOrBlank() && word.id != word.keyword) {
            onProblem(
                "'${WakeContract.META_ID}' \"${word.id}\" is not the keyword \"${word.keyword}\" derived from " +
                    "'${WakeContract.META_PHRASE}' — is that intended?",
            )
        }
        return word
    }

    /**
     * One optional confidence-ish field: absent/blank → [default]; present but not a number in
     * [[WakeContract.MIN_CONFIDENCE], [WakeContract.MAX_CONFIDENCE]] → reported against its own [field] name
     * and defaulted. Shared by [WakeContract.META_MIN_CONFIDENCE] and [WakeContract.META_SCORE_THRESHOLD] so
     * the two can't validate or report differently.
     */
    private fun confidence(
        raw: String?,
        default: Double,
        field: String,
        onProblem: (reason: String) -> Unit,
    ): Double {
        if (raw.isNullOrBlank()) return default
        val n = raw.trim().toDoubleOrNull()
        // `in` a floating-point range also excludes NaN (in no range) and Infinity, not just ordinary
        // out-of-bounds values, so a degenerate confidence can't slip through.
        if (n != null && n in WakeContract.MIN_CONFIDENCE..WakeContract.MAX_CONFIDENCE) return n
        onProblem(
            "'$field' is not a number in " +
                "${WakeContract.MIN_CONFIDENCE}..${WakeContract.MAX_CONFIDENCE}: " +
                "\"$raw\" — using default $default",
        )
        return default
    }
}
