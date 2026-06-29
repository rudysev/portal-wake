package com.portal.wake.audio

import android.content.Context
import com.portal.commons.PcmCaptureFormat
import com.portal.wake.audio.WakeMatcher.RecWord
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

/**
 * On-device multi-wake recognizer using **Vosk** (free, keyless, offline).
 *
 * Scope is deliberately narrow: loads the model, runs the decoder over PCM frames, and turns each
 * final result into a [WakeMatcher.RecWord] list. The accuracy *decision* lives in [WakeMatcher] (pure,
 * unit-tested); the mic lives in [WakeMicEngine]. Adding a "hey X" never touches this file.
 *
 * Deliberately log-free — the mic owner ([WakeMicEngine]) logs the lifecycle and matches. The model
 * loads asynchronously; [onReady] fires once the (warmed) recognizer can accept audio, [onUnavailable]
 * if the model assets are missing.
 */
class WakeRecognizer(
    context: Context,
    initialWakeWords: List<WakeWord>,
    private val onReady: () -> Unit,
    private val onUnavailable: () -> Unit,
) {
    @Volatile private var model: Model? = null

    @Volatile private var recognizer: Recognizer? = null

    // Closed before the async model load finished? The unpack callback honours this and frees rather than
    // assigning, so a teardown that races the load can't orphan a freshly-built (~128 MB) model + recognizer.
    @Volatile private var closed = false

    // The live wake set. Mutable because [rebuildGrammar] swaps the grammar in place (on a plugin
    // install/remove) without reloading the model — the model never changes, only the grammar does.
    @Volatile private var wakeWords: List<WakeWord> = initialWakeWords

    /**
     * Vosk JSON grammar built from the current wake phrases + their keywords + each word's declared lead
     * (e.g. "hey"), plus an "[unk]" escape token. "[unk]" absorbs non-matching speech so the decoder doesn't
     * force look-alikes onto a wake phrase; the per-phrase entries bias it toward a real wake — the single
     * biggest accuracy win. The lead comes from the wake word (declared by the plugin), not a hardcoded
     * "hey"; only the declared lead is added (not its [WakeMatcher.LEAD_ALIASES] mishearings), so the grammar
     * stays identical to what a "hey jarvis" produced before — on-device recall is unaffected.
     */
    private fun buildGrammar(words: List<WakeWord>): String {
        val entries = LinkedHashSet<String>()
        words.forEach {
            entries.add(it.phrase.lowercase())
            entries.add(it.keyword.lowercase())
            it.lead?.let { lead -> entries.add(lead.lowercase()) }
        }
        entries.add(WakeMatcher.UNK_TOKEN) // same token WakeMatcher's strict contamination gate rejects
        return entries.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }
    }

    init {
        // Unpack + load the model off the caller's thread; builds + warms the recognizer, then signals.
        try {
            org.vosk.android.StorageService.unpack(
                context,
                MODEL_ASSET,
                MODEL_TARGET,
                { m ->
                    // A close() that landed while the load was in flight: free the model instead of
                    // assigning it — nothing else holds a reference, so this is the only path that frees it.
                    if (closed) {
                        runCatching { m.close() }
                        return@unpack
                    }
                    model = m
                    val rec = buildRecognizer(m) // builds + warms up; null if grammar/recognizer init fails
                    // Re-check: close() can land while buildRecognizer runs. If so, free what we just built.
                    if (closed) {
                        runCatching { rec?.close() }
                        runCatching { m.close() }
                        model = null
                        return@unpack
                    }
                    recognizer = rec
                    if (rec != null) onReady() else onUnavailable()
                },
                { onUnavailable() },
            )
        } catch (
            @Suppress("SwallowedException")
            t: Throwable,
        ) {
            // The throwable is intentionally swallowed: don't let a model-unpack / native-init failure crash
            // the service. This class is deliberately log-free (see KDoc); by contract any init failure is
            // surfaced as the onUnavailable() signal, which the owner (WakeService) logs.
            onUnavailable()
        }
    }

    /**
     * Feed one frame of 16 kHz mono 16-bit PCM. Returns an [Outcome] when this frame finalizes a decode
     * that carries a wake keyword — a [Outcome.Match] on a genuine wake, or a [Outcome.NearMiss] when a
     * keyword decoded but a gate rejected it (so the mic owner can log the near-miss for tuning) — else null
     * (no finalized decode, or one with no registered keyword). No-op until the model has loaded. The decode
     * is carried so the mic owner can log *what Vosk heard*, the only way to diagnose a fire or a near-miss
     * after the fact.
     */
    fun accept(buf: ByteArray, n: Int): Outcome? {
        val rec = recognizer ?: return null
        if (!rec.acceptWaveForm(buf, n)) return null
        val words = parseResult(rec.result)
        return when (val o = WakeMatcher.evaluate(words, wakeWords)) {
            is WakeMatcher.Outcome.Match -> Outcome.Match(o.id, render(words))
            is WakeMatcher.Outcome.NearMiss -> Outcome.NearMiss(render(words), o.reason)
            WakeMatcher.Outcome.None -> null
        }
    }

    /** A finalized decode the mic owner should act on or log: a confirmed wake, or a logged near-miss. */
    sealed interface Outcome {
        /** A genuine wake: hand off to [id]. [transcript] is the decode, for the fire log. */
        data class Match(val id: String, val transcript: String) : Outcome

        /** A wake keyword decoded but a gate rejected it. [transcript] + [reason] go to the near-miss log. */
        data class NearMiss(val transcript: String, val reason: String) : Outcome
    }

    /** Render the decode as `word(conf%)` tokens (confidence omitted when the model gave none). */
    private fun render(words: List<RecWord>): String = words.joinToString(" ") { w ->
        if (w.conf < 0) w.word else "${w.word}(${(w.conf * 100).toInt()})"
    }

    /** Drop any partial decode state — called when (re)entering listening so stale audio doesn't linger. */
    fun reset() {
        runCatching { recognizer?.reset() }
        warmUp()
    }

    /**
     * Swap the grammar to a new wake set **without reloading the model** (the model never changes — only the
     * grammar does). Builds a fresh [Recognizer] from the already-loaded model, then atomically swaps it in
     * and closes the *old recognizer only*. Cheap (no [org.vosk.android.StorageService.unpack], no ~128 MB
     * model reload, no leaked unpack executor) — the structural fix for the per-package-update leak.
     *
     * MUST be called on the capture thread (same thread as [accept]) so we never close a native recognizer
     * while [accept]'s `acceptWaveForm` is in flight on it — the engine enforces this by applying the swap at
     * a frame boundary. If the model hasn't loaded yet, we just stash the new set; the pending unpack
     * callback builds from it. If the rebuild fails, the old recognizer AND the old [wakeWords] both keep
     * running, so [accept]'s matcher never disagrees with the live native grammar (we don't go deaf).
     */
    fun rebuildGrammar(newWakeWords: List<WakeWord>) {
        val m = model
        if (m == null) {
            wakeWords = newWakeWords // not loaded yet — the unpack callback will build from this
            return
        }
        val old = recognizer
        // Build from the candidate; commit wakeWords ONLY on success so the matcher (which reads wakeWords)
        // can't get ahead of the live native grammar if the build fails.
        val rec = buildRecognizer(m, newWakeWords) ?: return
        wakeWords = newWakeWords
        recognizer = rec
        if (old !== rec) runCatching { old?.close() }
    }

    fun close() {
        closed = true
        runCatching { recognizer?.close() }
        recognizer = null
        runCatching { model?.close() }
        model = null
    }

    private fun buildRecognizer(m: Model, words: List<WakeWord> = wakeWords): Recognizer? = runCatching {
        // Grammar-biased recognition toward the wake phrases; fall back to open vocabulary if the model
        // build rejects the grammar. setWords gives the per-word confidence the matcher gates on.
        val grammar = buildGrammar(words)
        val rec = runCatching { Recognizer(m, PcmCaptureFormat.SAMPLE_RATE.toFloat(), grammar) }
            .getOrElse { Recognizer(m, PcmCaptureFormat.SAMPLE_RATE.toFloat()) }
        runCatching { rec.setWords(true) }

        warmUpRecognizer(rec)
        rec
    }.getOrNull()

    /**
     * Force Kaldi's one-time lazy decoding-graph allocation with a silent frame. Called during model init
     * (off the capture thread) and after every [reset] (on the capture thread) so the first live frame
     * after a handoff/resume is not garbled by an in-read allocation stall.
     */
    private fun warmUp() {
        warmUpRecognizer(recognizer)
    }

    private fun warmUpRecognizer(rec: Recognizer?) {
        runCatching {
            val r = rec ?: return
            // Prime Kaldi's online decoder with ~1 s of silence, not just one frame. A single frame forces
            // the lazy decoding-graph allocation, but the online-decoding state needs more run-up to settle:
            // otherwise the FIRST word of the first utterance after a reset (fresh start, handoff reclaim,
            // idle reset) is dropped — observed on-device as `[jarvis(..)] rejected: no 'hey'` misses where
            // the leading "hey" never decodes. Feeding silence (cheap) settles it so "hey jarvis" lands whole.
            val silence = ByteArray(PcmCaptureFormat.FRAME_BYTES)
            repeat(WARMUP_SILENCE_FRAMES) { r.acceptWaveForm(silence, silence.size) }
        }
    }

    companion object {
        const val MODEL_ASSET = "model-en-us" // assets/model-en-us/
        const val MODEL_TARGET = "vosk-model" // unpacked into filesDir
        const val NO_CONF = -1.0 // sentinel: recognizer gave no per-word confidence
        const val WARMUP_SILENCE_FRAMES = 10 // ~1 s of silence to settle the online decoder after a reset

        /**
         * Parse a Vosk final-result JSON into recognized words. Prefers per-word confidence (from
         * `setWords(true)`); falls back to the plain transcript with unknown confidence. Pure + static,
         * so it is unit-tested.
         */
        fun parseResult(json: String): List<RecWord> {
            val obj = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
            val arr = obj.optJSONArray("result")
            if (arr != null && arr.length() > 0) {
                return (0 until arr.length()).mapNotNull { idx ->
                    val o = arr.optJSONObject(idx) ?: return@mapNotNull null
                    RecWord(o.optString("word"), o.optDouble("conf", NO_CONF))
                }
            }
            val text = obj.optString("text").trim()
            if (text.isEmpty()) return emptyList()
            return text.split(Regex("\\s+")).map { RecWord(it, NO_CONF) }
        }
    }
}
