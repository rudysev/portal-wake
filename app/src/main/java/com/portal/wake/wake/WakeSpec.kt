package com.portal.wake.wake

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
     *  - [minConfidence] absent/blank, or present but not a number in [[WakeContract.MIN_CONFIDENCE],
     *    [WakeContract.MAX_CONFIDENCE]] → falls back to [defaultMinConf]. A malformed value is reported
     *    (loud) but does **not** reject the word — the field is optional, so a typo here shouldn't disable
     *    an otherwise-valid wake word;
     *  - [id] absent/blank → the keyword (handled in [WakeWord.fromPhrase]).
     */
    fun build(
        phrase: String?,
        id: String?,
        minConfidence: String?,
        defaultMinConf: Double,
        onProblem: (reason: String) -> Unit,
    ): WakeWord? {
        if (phrase.isNullOrBlank()) {
            onProblem("missing required '${WakeContract.META_PHRASE}'")
            return null
        }
        val minConf = minConfOrDefault(minConfidence, defaultMinConf, onProblem)
        // phrase is non-blank (guarded above), so fromPhrase always yields a usable word.
        val word = WakeWord.fromPhrase(phrase, id, minConf)
        // A provided id that isn't the derived keyword is usually a typo (e.g. phrase="hey vega",
        // id="jarvis"). It still builds (an id may legitimately differ), but flag it — the id is what's
        // reported on a match and what overrides a built-in default.
        if (word != null && !id.isNullOrBlank() && word.id != word.keyword) {
            onProblem(
                "'${WakeContract.META_ID}' \"${word.id}\" is not the keyword \"${word.keyword}\" derived from " +
                    "'${WakeContract.META_PHRASE}' — is that intended?",
            )
        }
        return word
    }

    /**
     * The declared [minConfidence], or [defaultMinConf] when it is absent, or reported via [onProblem] when invalid.
     */
    private fun minConfOrDefault(
        minConfidence: String?,
        defaultMinConf: Double,
        onProblem: (reason: String) -> Unit,
    ): Double {
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
