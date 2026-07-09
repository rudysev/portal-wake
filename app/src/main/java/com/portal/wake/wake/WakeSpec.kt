package com.portal.wake.wake

import com.portal.commons.audio.WakeWord

/**
 * Builds a [WakeWord] from one wake plugin's declared meta-data fields (see [WakeContract]). Reading the
 * raw values off the manifest `Bundle` is the caller's job ([WakeRegistry]); this validates them and turns
 * them into a [WakeWord].
 */
object WakeSpec {

    /**
     * Validate the declared fields and build a [WakeWord], reporting any problem via [onProblem]:
     *  - [phrase] missing/blank → **rejected** (returns null);
     *  - [minConfidence] absent/blank, or present but not a number in [[WakeContract.MIN_SCORE_THRESHOLD],
     *    [WakeContract.MAX_SCORE_THRESHOLD]] → falls back to [defaultScoreThreshold];
     *  - [id] absent/blank → the keyword (handled in [WakeWord.fromPhrase]).
     */
    fun build(
        phrase: String?,
        id: String?,
        minConfidence: String?,
        defaultScoreThreshold: Double,
        onProblem: (reason: String) -> Unit,
    ): WakeWord? {
        if (phrase.isNullOrBlank()) {
            onProblem("missing required '${WakeContract.META_PHRASE}'")
            return null
        }
        val scoreThreshold: Double
        if (minConfidence.isNullOrBlank()) {
            scoreThreshold = defaultScoreThreshold
        } else {
            val n = minConfidence.trim().toDoubleOrNull()
            if (n != null && n in WakeContract.MIN_SCORE_THRESHOLD..WakeContract.MAX_SCORE_THRESHOLD) {
                scoreThreshold = n
            } else {
                onProblem(
                    "'${WakeContract.META_MIN_CONFIDENCE}' is not a classifier score in " +
                        "${WakeContract.MIN_SCORE_THRESHOLD}..${WakeContract.MAX_SCORE_THRESHOLD}: " +
                        "\"$minConfidence\" — using default $defaultScoreThreshold",
                )
                scoreThreshold = defaultScoreThreshold
            }
        }
        val word = WakeWord.fromPhrase(phrase, id, scoreThreshold) ?: return null
        if (!id.isNullOrBlank() && word.id != word.keyword) {
            onProblem(
                "'${WakeContract.META_ID}' \"${word.id}\" is not the keyword \"${word.keyword}\" derived from " +
                    "'${WakeContract.META_PHRASE}' — is that intended?",
            )
        }
        return word
    }
}
