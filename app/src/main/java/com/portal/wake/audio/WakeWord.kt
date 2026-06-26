package com.portal.wake.audio

/**
 * One wake phrase the recognizer listens for.
 *
 * This is intentionally decoupled from *what happens* on a match — routing/handoff lives in
 * [com.portal.wake.wake.WakeTarget], which pairs a [WakeWord] with the app that should be launched.
 * Keeping the matcher target-agnostic is what lets wake words come from anywhere (built-in defaults
 * today, runtime-discovered plugin apps tomorrow) without touching the recognizer.
 *
 * @param id      stable key reported back on a match (e.g. "jarvis", "alexa").
 * @param phrase  the full spoken phrase, also a grammar entry (e.g. "hey jarvis").
 * @param keyword the salient word the matcher looks for, preceded by a "hey"-word (e.g. "jarvis").
 * @param minConf keyword-confidence floor to accept this wake word. At or below
 *   [WakeMatcher.BASELINE_CONF] the lenient clean-phrase bypass applies (a clean short "<hey> <keyword>"
 *   fires even if the model under-scores it — dormant insurance with the lgraph model, see [WakeMatcher]);
 *   above it the bypass is off and the keyword must clear this floor. A higher value mainly trades a bit
 *   of recall for a stricter floor; note precision comes from the required "hey", not this number.
 */
data class WakeWord(
    val id: String,
    val phrase: String,
    val keyword: String,
    val minConf: Double,
)
