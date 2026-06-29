package com.portal.wake.audio

/**
 * One wake phrase the recognizer listens for.
 *
 * This is intentionally decoupled from *what happens* on a match — routing/handoff lives in
 * [com.portal.wake.wake.WakeTarget], which pairs a [WakeWord] with the app that should be launched.
 * Keeping the matcher target-agnostic is what lets wake words come from anywhere (built-in defaults
 * today, runtime-discovered plugin apps tomorrow) without touching the recognizer.
 *
 * A wake word is a [keyword] (the salient word the matcher spots) optionally preceded by a [lead] word
 * (e.g. "hey", "hi"). The lead is **declared by the plugin** (it's the leading word of the phrase it
 * registers), not hardcoded here, so an app can register "hey jarvis", "hi bob", or a bare "computer"
 * (no lead). The full [phrase] is derived from the two.
 *
 * @param id      stable key reported back on a match (e.g. "jarvis", "alexa").
 * @param keyword the salient word the matcher spots (e.g. "jarvis").
 * @param lead    the word that must precede [keyword] (e.g. "hey", "hi"), or null when none is required.
 *   Precision comes from this declared lead, not a hardcoded "hey" — see [WakeMatcher].
 * @param minConf keyword-confidence floor to accept this wake word. At or below
 *   [WakeMatcher.BASELINE_CONF] the lenient clean-phrase bypass applies (a clean short "<lead> <keyword>"
 *   fires even if the model under-scores it — dormant insurance with the lgraph model, see [WakeMatcher]);
 *   above it the bypass is off and the keyword must clear this floor. A higher value mainly trades a bit
 *   of recall for a stricter floor; precision comes from the required [lead], not this number.
 */
data class WakeWord(
    val id: String,
    val keyword: String,
    val lead: String?,
    val minConf: Double,
) {
    /** The full spoken phrase / grammar entry, derived from [lead] + [keyword] (e.g. "hey jarvis"). */
    val phrase: String get() = lead?.let { "$it $keyword" } ?: keyword

    companion object {
        private val WHITESPACE = Regex("\\s+")

        /** Split a phrase into its lowercase, whitespace-separated words — the one shared tokenization rule
         *  ([fromPhrase] and the registry's word-count both use it, so they can't drift). */
        fun tokenize(phrase: String): List<String> = phrase.trim().lowercase().split(WHITESPACE).filter { it.isNotEmpty() }

        /**
         * Derive a [WakeWord] from a full spoken [phrase] — the single place phrase→(keyword, lead) lives,
         * so plugin declarations and the built-in defaults can never derive differently. The **keyword** is
         * the last word and the **lead** is the word immediately before it (null when the phrase is a single
         * word). [id] defaults to the keyword when blank/omitted. Lowercased and whitespace-trimmed.
         *
         * "hey jarvis" → keyword "jarvis", lead "hey"; "hi bob" → keyword "bob", lead "hi";
         * "computer" → keyword "computer", lead null. A phrase with >2 words keeps only the last two
         * (lead is a single token by design). Returns null when [phrase] is blank / whitespace-only (no
         * usable word) — the single guard, so callers never have to pre-check and a bad declaration is dropped.
         */
        fun fromPhrase(phrase: String, id: String? = null, minConf: Double): WakeWord? {
            val words = tokenize(phrase)
            val keyword = words.lastOrNull() ?: return null
            val lead = if (words.size >= 2) words[words.size - 2] else null
            return WakeWord(
                id = id?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: keyword,
                keyword = keyword,
                lead = lead,
                minConf = minConf,
            )
        }
    }
}
