package com.portal.wake.audio

/**
 * The wake-trigger **decision**, as a pure function — no Vosk, no Android, no I/O — so it is fully
 * unit-testable and the accuracy policy lives in one obvious place.
 *
 * Two route classes, split by [WakeWord.minConf] vs [BASELINE_CONF]:
 *
 *  - **Strict routes** (`minConf > BASELINE_CONF` — the hand-off words jarvis/alexa, *and any plugin word
 *    above the baseline*, e.g. 0.55). A wake fires only on a **clean, uncontaminated phrase**: **no
 *    `[unk]`** ([UNK_TOKEN]), ≤ [CLEAN_PHRASE_MAX_WORDS] words, a **confident** preceding lead — the word's
 *    declared lead, e.g. "hey" (≥ [LEAD_MIN_CONF]) — the keyword over its floor, and **no rival wake
 *    keyword** in the decode. The mandatory lead is the first guard (a bare keyword, look-alike, or
 *    noise-decoded keyword never fires),
 *    but for a soundalike-prone keyword it is **not sufficient** that one is merely *present*: background
 *    audio (TV/music, or a phone call on a speaker) can decode a full "hey jarvis" — inside a long
 *    `[unk]`-laden final (observed: `[unk] alexa [unk] [unk] hey jarvis`, jarvis at 0.83), as a short
 *    contaminated phrase (observed live: `[unk] hey jarvis`, both words at ~0.99 — *every* confidence floor
 *    cleared), or with a *weak* "hey" (during a phone call nearby: `[unk] hey jarvis`, hey at 0.66). The
 *    **no-`[unk]`** gate blocks the first two — a genuine close-mic wake decodes as a bare "<lead> <keyword>"
 *    with no `[unk]`, so it costs no recall and catches what no confidence floor can (both keyword and lead
 *    score ~1.00 in a contaminated final); the [LEAD_MIN_CONF] floor blocks the weak-lead case. Raising the
 *    *keyword* floor would block none of them — the FPs scored above any floor we'd keep, while real wakes
 *    score ~1.00. **Trade-off:** strict routes do *not* fire on an embedded wake ("hey could you ask jarvis")
 *    or one Vosk pads with an `[unk]` — rare, and not worth the false fires.
 *  - **Lenient routes** (`minConf <= BASELINE_CONF`, the default for a plugin that omits a floor). For
 *    recall insurance a short, clean "<lead> <keyword>" fires even at low confidence, and an embedded
 *    high-confidence keyword fires too. With the bundled lgraph model this bypass is **dormant** (real
 *    wakes decode at ≥0.92), so it's a safety net, not load-bearing.
 *
 * See `TUNING.md` for the on-device data behind these dials.
 */
object WakeMatcher {

    /** Confidence floor at/below which a word qualifies for the lenient clean-phrase bypass. */
    const val BASELINE_CONF = 0.50

    /**
     * Strict per-word floor for a route that wants real confidence: it sits **above** [BASELINE_CONF], so
     * the lenient clean-phrase bypass is OFF and the keyword must actually clear this score. The built-in
     * jarvis + alexa defaults use it (see [com.portal.wake.wake.WakeRegistry]).
     */
    const val STRICT_MIN_CONF = 0.60

    /** Utterances this short (in words) count as a "clean phrase" eligible for the lenient bypass. */
    const val CLEAN_PHRASE_MAX_WORDS = 3

    /**
     * Confidence floor the preceding lead (e.g. "hey") must clear on a **strict** route. A real wake decodes
     * its lead at 0.96–1.00 (see `TUNING.md`), so this is essentially free recall-wise, but it blocks a
     * background-audio FP that assembles a short clean "hey jarvis" with an under-confident "hey" — observed
     * live at 0.66 during a phone call held near the Portal. Lenient routes do not apply it (present-only, for recall).
     */
    const val LEAD_MIN_CONF = 0.80

    /**
     * Recognizer variants accepted for a declared lead word (open recognition mishears it). The lead itself
     * is **declared by the wake plugin** (e.g. "hey", "hi" — the first word of its phrase); this map only
     * adds known mishearings on top, so a lead with no entry matches itself only. "hey" tolerates "hay"
     * (Vosk's frequent mishearing); `a`, `hi`, and `he` were deliberately *not* added to "hey" as
     * too-common lead-ins that widened false fires.
     */
    val LEAD_ALIASES: Map<String, Set<String>> = mapOf("hey" to setOf("hey", "hay"))

    /**
     * The words accepted as [WakeWord.lead] for [w] — its [LEAD_ALIASES] variants, or just the lead itself —
     * or null when the word declares no lead (then no preceding-word gate applies).
     */
    fun acceptedLeads(w: WakeWord): Set<String>? = w.lead?.let { LEAD_ALIASES[it] ?: setOf(it) }

    /**
     * Vosk's "unknown" escape token — the single source of truth for it: [WakeRecognizer.buildGrammar] adds
     * this exact string to the grammar and this gate rejects it, so the two can't drift. Its presence in a
     * final decode means non-wake audio was captured in the same window — contamination a **strict** route
     * rejects, since a genuine close-mic wake never decodes one.
     */
    const val UNK_TOKEN = "[unk]"

    /** One recognized word and its confidence (−1 when the recognizer gave no per-word score). */
    data class RecWord(val word: String, val conf: Double)

    /**
     * Outcome of evaluating a decode against the wake set — a [Match], a [NearMiss] (a wake keyword decoded
     * but a gate rejected it, carried with the failing gate's reason for the diagnostic log), or [None]
     * (nothing wake-like, so not worth logging — ambient speech with no registered keyword).
     */
    sealed interface Outcome {
        data class Match(val id: String) : Outcome
        data class NearMiss(val keyword: String, val reason: String) : Outcome
        data object None : Outcome
    }

    /**
     * Returns the id of the first [wakeWords] entry genuinely spoken in [words], or null. Deterministic
     * and side-effect-free. Thin wrapper over [evaluate] — kept as the load-bearing accuracy entry point.
     */
    fun match(words: List<RecWord>, wakeWords: List<WakeWord>): String? = (evaluate(words, wakeWords) as? Outcome.Match)?.id

    /**
     * Like [match] but also reports *why* a decode that carried a wake keyword was rejected, so the mic owner
     * can log near-misses for tuning. Returns the first [Outcome.Match] if one fires; otherwise the first
     * keyword-present [Outcome.NearMiss] (a real wake keyword decoded but a gate blocked it); otherwise
     * [Outcome.None]. The gate order is identical to [match] — this only adds the rejection reason.
     */
    fun evaluate(words: List<RecWord>, wakeWords: List<WakeWord>): Outcome {
        if (words.isEmpty()) return Outcome.None
        val lower = words.map { RecWord(it.word.lowercase(), it.conf) }
        var nearMiss: Outcome.NearMiss? = null // first keyword-present rejection, if no word matches
        for (w in wakeWords) {
            val keyword = w.keyword.lowercase()
            // Use the LAST occurrence of the keyword so a tail "<hey> <keyword>" still has a preceding
            // "hey" to find, even if the keyword was also said incidentally earlier; indexOfFirst would
            // stop at the early mention (no preceding "hey") and bail. NB: for a *strict* route the whole
            // utterance must still be a clean phrase, so a long "…ask jarvis … oh wait hey jarvis" is
            // rejected regardless — this last-occurrence rule matters most to the lenient/embedded path.
            val i = lower.indexOfLast { it.word == keyword }
            if (i < 0) continue
            val reason = rejectionReason(lower, i, w, wakeWords) ?: return Outcome.Match(w.id)
            if (nearMiss == null) nearMiss = Outcome.NearMiss(keyword, reason)
        }
        return nearMiss ?: Outcome.None
    }

    /**
     * Run the per-keyword gates (same order as [evaluate]) for the keyword at index [i]; returns null if the
     * word fires, else a human-readable reason for the rejection. Pure — the single place the gate sequence
     * lives, so [match] and the near-miss log can never disagree.
     */
    private fun rejectionReason(lower: List<RecWord>, i: Int, w: WakeWord, wakeWords: List<WakeWord>): String? {
        // Must be preceded by the word's declared lead (e.g. "hey", "hi"). A word with no declared lead
        // (leads == null) skips this prefix gate.
        val before = lower.subList(0, i)
        val leads = acceptedLeads(w)
        if (leads != null && before.none { it.word in leads }) return "no '${w.lead}' before '${w.keyword}'"

        val cleanPhrase = lower.size <= CLEAN_PHRASE_MAX_WORDS
        if (w.minConf > BASELINE_CONF) {
            // Strict route = any floor above BASELINE_CONF (the jarvis/alexa defaults, and any plugin
            // word > 0.50). Demand an UNCONTAMINATED, CLEAN phrase, a CONFIDENT lead, the keyword over its
            // floor, and no rival wake keyword — see the class KDoc for why mere lead *presence* isn't enough.
            // Contamination first: an "[unk]" in the final means non-wake audio decoded in the same window. A
            // genuine close-mic wake decodes as a bare "<hey> <keyword>" with no "[unk]" (observed across every
            // real fire); a leading/trailing "[unk]" is the tell-tale of background audio assembling a wake
            // (observed live FP: `[unk](51) hey(99) jarvis(99)` — both words maxed, so no confidence floor
            // could catch it). Strict = uncontaminated, so reject any "[unk]" outright.
            if (lower.any { it.word == UNK_TOKEN }) return "contaminated ('$UNK_TOKEN' in decode)"
            if (!cleanPhrase) return "phrase too long (${lower.size} words > $CLEAN_PHRASE_MAX_WORDS)"
            if (leads != null && before.none { it.word in leads && it.conf >= LEAD_MIN_CONF }) {
                return "'${w.lead}' under $LEAD_MIN_CONF floor"
            }
            if (lower[i].conf < w.minConf) return "'${w.keyword}' ${conf(lower[i].conf)} under ${w.minConf} floor"
            if (containsRivalKeyword(lower, w, wakeWords)) return "rival wake keyword in decode"
            return null
        }
        // Lenient route (e.g. a low-floor custom word): a clean short phrase fires even under-confident
        // (the recall net), else the keyword must clear its floor.
        if (cleanPhrase || lower[i].conf >= w.minConf) return null
        return "'${w.keyword}' ${conf(lower[i].conf)} under ${w.minConf} floor"
    }

    /** Render a confidence for a log reason (`conf%`, or `?` when the recognizer gave no score). */
    private fun conf(c: Double): String = if (c < 0) "?" else "${(c * 100).toInt()}%"

    /**
     * A genuine "hey jarvis" never also contains "alexa": a decode carrying a *different* registered wake
     * keyword is suspect. Sees only the active [all] list (= the recognizer's grammar keywords), so an
     * unregistered keyword wouldn't decode here anyway — the clean-phrase gate is the primary defense.
     */
    private fun containsRivalKeyword(words: List<RecWord>, self: WakeWord, all: List<WakeWord>): Boolean {
        val present = words.mapTo(HashSet()) { it.word }
        return all.any { it.id != self.id && it.keyword.lowercase() in present }
    }
}
