package com.portal.wake.audio

import android.content.Context
import com.portal.commons.DebugLog
import com.portal.commons.PcmCaptureSession
import com.portal.commons.audio.AudioRecordPcmDevice
import com.portal.wake.system.MicLiberator

/**
 * Wake **recognition policy** over the shared [PcmCaptureSession]: it owns the [WakeRecognizer], the
 * pre-ready frame buffer, and the post-fire cooldown, while the session owns the capture thread + device
 * lifecycle (open/read/robust stop/rebuild). A matched frame invokes `onWake(id)`.
 *
 * **Per-frame policy** ([onFrame], capture thread): while the model is still loading, ring-buffer frames
 * (so speech during init isn't lost); on the ready transition, flush them; then feed the recognizer with a
 * post-fire cooldown. **On (re)start** ([onStarted]) the recognizer is reset — which re-warms it — so the
 * first frame after a resume isn't garbled.
 *
 * **Mic-slot handoff & phone calls are NOT handled here** anymore — `WakeService` drives [pause]/[start]
 * for both handoff and call stand-down via its capture gate. [pause] yields the slot: the session's reads
 * are non-blocking, so its stop deterministically exits the capture thread and releases the mic. [start]
 * reacquires it; [MicLiberator] best-effort frees the slot from the
 * Portal's own wake app on acquire.
 *
 * Caller must hold RECORD_AUDIO. If the wake model is missing/unusable, [onUnavailable] fires.
 */
class WakeMicEngine(
    private val context: Context,
    wakeWords: List<WakeWord>,
    onUnavailable: () -> Unit,
    private val onWake: (String) -> Unit = {},
    private val onError: (String) -> Unit = {},
    private val onStopped: () -> Unit = {},
) {
    @Volatile private var recognizerReady = false // set when the model finishes unpacking (warmed)
    private var wasRecognizerReady = false // capture-thread-only; detects ready transition for buffer flush

    @Volatile private var pendingWakeWords: List<WakeWord>? = null // queued wake-set swap, applied on capture thread
    private var lastResetAtMs = 0L // capture-thread-only; bounds the Vosk decode lattice (idle reset)
    private val preReadyBuffer = PcmRingBuffer(PRE_READY_MAX_FRAMES)
    private val recognizer = WakeRecognizer(
        context,
        wakeWords,
        onReady = {
            recognizerReady = true
            DebugLog.log("wake recognizer ready")
        },
        onUnavailable = onUnavailable,
    )

    private var cooldownUntil = 0L // capture-thread-only; reset on each (re)start

    // Rebuildable: a wedged capture thread (start() refused) is recovered by discarding the session and
    // building a fresh one (new device + thread, no shared state with the wedged one). start/pause/shutdown
    // all run on the single controlling thread (WakeService's arbiter), so this needs no mutual exclusion;
    // @Volatile is just cheap insurance that the reassignment is visible — it's a recovery-path mutable ref.
    @Volatile private var session = buildSession()

    private fun buildSession(): PcmCaptureSession {
        lateinit var built: PcmCaptureSession
        built = PcmCaptureSession(
            device = AudioRecordPcmDevice(),
            onFrame = ::onFrame, // the moved post-read guard means a stopped session's thread never calls this
            onStarted = ::onStarted,
            // Forward stop/error only from the CURRENT session. A rebuilt-away (wedged) session's zombie still
            // fires onStopped when its native call finally unblocks; WakeService's engineGeneration guard does
            // NOT cover an internal session rebuild (same engine), so without this it would clear `capturing`
            // while the fresh session is live. `session` is @Volatile, so the old capture thread sees the
            // current reference and a stale session's callback is dropped.
            onStopped = { if (session === built) onStopped() },
            onError = { if (session === built) onError(it) },
            log = { DebugLog.log(it) },
            threadName = "wake-capture",
            rebuildAfterReadFailures = REBUILD_AFTER_READ_FAILURES,
            // Always-listening: recover a stolen/half-dead mic that returns no data (read==0) without ever
            // erroring — otherwise the <0 rebuild above can't see it and wake goes silently deaf.
            idleRebuildMs = PcmCaptureSession.DEFAULT_IDLE_REBUILD_MS,
        )
        return built
    }

    /**
     * Open the mic and start capturing (idempotent). Frees the Portal wake-word services first so we own the
     * single mic slot. The session refuses to start only while a prior capture thread is still alive —
     * which can happen only if a native open/stop/release hung. Recover by discarding it and starting a fresh
     * session, so a stuck native mic call can't leave us permanently deaf. Returns whether a capture thread is now running.
     * Callbacks are wired in [buildSession].
     */
    fun start(): Boolean {
        MicLiberator.freeMic(context)
        if (session.start()) return true
        // Refused → a previous capture thread is still alive: a native open/stop/release hung past the stop
        // join. Discard it and start fresh on a new device/thread. The stale thread sees running=false so it
        // delivers no further frame, and its eventual onStopped is dropped by the current-session gate (in
        // buildSession), so it can't disturb the fresh capture.
        DebugLog.log("wake capture wedged — rebuilding session and retrying")
        session = buildSession()
        return session.start()
    }

    /**
     * Swap the wake set (a plugin was installed/removed) **without restarting capture or reloading the
     * model**. The swap is queued here and applied on the capture thread in [onFrame] at a frame boundary —
     * never closing a native recognizer mid-`acceptWaveForm`. Cheap: only the grammar changes (see
     * [WakeRecognizer.rebuildGrammar]). Replaces the old tear-down-and-recreate-the-engine path.
     */
    fun updateWakeWords(words: List<WakeWord>) {
        pendingWakeWords = words
    }

    /** Release the mic so a consumer (or a call) can take the slot. Non-blocking reads make the session's stop deterministic: the capture thread exits promptly and the mic is freed. */
    fun pause() {
        session.stop()
        DebugLog.log("mic paused (yielded slot)")
    }

    /** Full teardown: release the mic and close the wake model. */
    fun shutdown() {
        session.stop()
        recognizer.close()
    }

    // ---- recognition policy (capture thread) -------------------------------------------------------

    /** Reset recognizer state (re-warms) and the ready-transition flag on each (re)start. */
    private fun onStarted() {
        wasRecognizerReady = false
        cooldownUntil = 0L
        recognizer.reset()
        lastResetAtMs = System.currentTimeMillis()
    }

    private fun onFrame(buf: ByteArray, n: Int) {
        // Apply a queued wake-set swap on THIS (capture) thread, at a frame boundary — so the native
        // recognizer is never closed mid-`acceptWaveForm` (see [WakeRecognizer.rebuildGrammar]).
        applyPendingWakeWords()
        // While the model loads, ring-buffer recent frames so speech during init isn't lost.
        if (!recognizerReady) {
            preReadyBuffer.add(buf, n)
            return
        }
        if (!wasRecognizerReady) {
            wasRecognizerReady = true
            flushPreReadyBuffer()
        }
        maybeIdleReset()
        tryAcceptFrame(buf, n)
    }

    /** Apply a queued [updateWakeWords] on the capture thread. No-op when nothing is pending. */
    private fun applyPendingWakeWords() {
        val words = pendingWakeWords ?: return
        pendingWakeWords = null
        DebugLog.log("wake set changed → rebuilding grammar (${words.size} word(s))")
        recognizer.rebuildGrammar(words)
        lastResetAtMs = System.currentTimeMillis() // fresh recognizer — restart the idle-reset clock
    }

    /**
     * Bound Vosk's native decode lattice. With continuous ambient audio the grammar recognizer can go a long
     * time without endpointing, so its "current utterance" never finalizes and the native lattice grows
     * (~3.8 MB/min observed). Forcing a periodic [WakeRecognizer.reset] (which re-warms) caps that growth.
     * Skipped during the post-fire cooldown so we never reset mid-handoff.
     */
    private fun maybeIdleReset() {
        val now = System.currentTimeMillis()
        if (now < cooldownUntil) return
        if (now - lastResetAtMs < IDLE_RESET_MS) return
        recognizer.reset()
        lastResetAtMs = now
        DebugLog.log("idle reset (bounding Vosk lattice)")
    }

    /** Feed buffered pre-ready frames to the recognizer once the model becomes ready. */
    private fun flushPreReadyBuffer() {
        val buffered = preReadyBuffer.drain()
        if (buffered.isEmpty()) return
        DebugLog.log("flushing ${buffered.size} pre-ready frame(s)")
        for (frame in buffered) {
            tryAcceptFrame(frame, frame.size)
        }
    }

    /** Run one frame through Vosk; fire [onWake] on a match, log a near-miss (respects post-fire cooldown). */
    private fun tryAcceptFrame(buf: ByteArray, n: Int) {
        if (System.currentTimeMillis() < cooldownUntil) return
        when (val outcome = recognizer.accept(buf, n)) {
            is WakeRecognizer.Outcome.Match -> {
                // Log the decode (word + conf%) so a false fire shows exactly what Vosk heard, e.g.
                // `wake detected → jarvis [hey(99) jarvis(62)]`.
                DebugLog.log("wake detected → ${outcome.id} [${outcome.transcript}]")
                cooldownUntil = System.currentTimeMillis() + COOLDOWN_MS
                // No reset() on the fire path: cooldown blocks further accept()s, handoff pauses capture, and
                // onStarted() re-warms on resume — so a match-path reset is redundant work on the hot path.
                onWake(outcome.id)
            }

            is WakeRecognizer.Outcome.NearMiss -> {
                // A wake keyword decoded but a gate rejected it — log what Vosk heard and which gate failed
                // so a real miss leaves a trace, e.g. `near-miss [hey(72) jarvis(95)] rejected: 'hey' under 0.8 floor`.
                DebugLog.log("near-miss [${outcome.transcript}] rejected: ${outcome.reason}")
            }

            null -> {}
        }
    }

    private companion object {
        const val COOLDOWN_MS = 1_500L // ignore further matches briefly after a fire
        const val PRE_READY_MAX_FRAMES = 80 // ~8 s of capture frames — must exceed model warm-up (~6 s)
        const val REBUILD_AFTER_READ_FAILURES = 40 // rebuild the device after a short run of read errors
        const val IDLE_RESET_MS = 25_000L // cap Vosk's native lattice: force a recognizer reset this often
    }
}
