package com.portal.wake.service

import com.portal.commons.DebugLog
import com.portal.wake.wake.HandoffRecovery
import com.portal.wake.wake.HandoffRecovery.Reclaim
import com.portal.wake.wake.MicContentionDetector

/**
 * Owns the **mic-arbitration decision flow** that used to live inline in [WakeService]: it holds the two
 * capture pause reasons (call, handoff) and wires the pure coordinators ([HandoffRecovery],
 * [MicContentionDetector]) to two injected seams — a [Scheduler] (the controlling thread's delayed posts) and
 * an [AudioGate] (the audio signals it reads) — and drives capture start/pause through the [CaptureController]
 * seam. [WakeService] keeps the engine lifecycle, the match→consumer routing, and the thin Android adapters; this class is the
 * "should we be holding the mic right now" state machine.
 *
 * **No Android framework types.** Everything Android (the `AudioManager` reads, the
 * `AudioRecordingConfiguration` callback, the `Handler`) lives behind [AudioGate]/[Scheduler] in
 * [WakeService]; the arbiter's only non-arbiter dependency is commons' pure-JVM `DebugLog`. So the whole
 * arbiter — including the historically churn-prone reclaim debounce — is
 * exercised in plain-JVM unit tests with fakes + an injected [now] clock (see `MicArbiterTest`).
 *
 * **Threading.** The seams are driven on one controlling thread (the service's main `Handler` in prod): the
 * recording callback is delivered on it, the poll re-posts to it, and the reclaim debounce is a delayed post
 * on it — so the coordinators are touched single-threaded. The engine's capture thread never calls in here.
 *
 * The single reconcile rule ([reconcile]) is the only place capture is started or paused, so the two pause
 * reasons (call + handoff) can't fight: they are tracked independently, so a call arriving mid-handoff just
 * keeps capture paused until both clear.
 */
internal class MicArbiter(
    private val audio: AudioGate,
    private val scheduler: Scheduler,
    private val controller: CaptureController,
    private val now: () -> Long = System::currentTimeMillis,
) {

    /**
     * The engine-side seam: [MicArbiter] *decides*, [WakeService] *applies* the decision to the real
     * [com.portal.commons.audio.WakeMicEngine]. Keeping the engine out of the arbiter is what lets the
     * arbitration logic be reasoned about (and, with a fake controller + clock, unit-tested) on its own.
     */
    interface CaptureController {
        /** Try to start capture; returns true **iff** capture actually started (a refused/zombie start = false). */
        fun startCapture(): Boolean

        /** Pause capture (no-op if already paused). */
        fun pauseCapture()

        /** Whether capture is currently running (reflects the last applied start/pause). */
        val isCapturing: Boolean
    }

    /** Delayed-post seam on the controlling thread. Prod wraps the service's main `Handler`; tests use a fake. */
    interface Scheduler {
        fun postDelayed(action: Runnable, delayMs: Long)

        fun removeCallbacks(action: Runnable)
    }

    /** The Android audio signals the arbiter reads, behind a seam so the arbiter stays pure-JVM testable. */
    interface AudioGate {
        /** Whether a phone/VoIP call is ringing or active, read live. The "not `MODE_NORMAL`" rule lives in the
         *  production adapter, so no raw audio-mode int crosses this seam. */
        val inCall: Boolean

        /** Whether any recording is currently active. A presence check — the reclaim confirm needs "is anyone
         *  recording?", not the ids (those arrive pushed via [onRecordingConfigs]) — so no list is allocated. */
        val hasActiveRecording: Boolean
    }

    private val recovery = HandoffRecovery()
    private val contention = MicContentionDetector()

    // The two independent capture pause reasons; capture runs only when neither holds (see [shouldCapture]).
    private var pausedForCall = false
    private var pausedForHandoff = false

    private val shouldCapture: Boolean get() = !pausedForCall && !pausedForHandoff

    /** Status label for the per-reason transition logs; **call takes precedence** when both reasons hold. */
    private val status: String
        get() = when {
            pausedForCall -> "paused for call"
            pausedForHandoff -> "paused for handoff"
            else -> "listening"
        }

    /**
     * Confirms a reclaim after [RECLAIM_DEBOUNCE_MS]: only take the mic back if no one is still recording.
     * Absorbs the transient empty-config callback emitted while our own capture stops (which would otherwise
     * reclaim instantly and re-grab the mic from a consumer that never actually released — the foreground-tap
     * race). If a recorder is still present, tell recovery and keep waiting.
     */
    private val reclaimConfirm = Runnable {
        if (audio.hasActiveRecording) {
            recovery.onReleaseUnconfirmed() // still here → BORROWED, await real release
            DebugLog.log("reclaim debounced — consumer still recording")
            return@Runnable
        }
        DebugLog.log("consumer released mic (confirmed) → reclaiming")
        reclaimNow()
    }

    /**
     * The call stand-down poll. API 28 has no audio-mode-change broadcast, so we re-read [AudioGate.inCall] on
     * the controlling thread for the service's life. It re-posts itself; [detach] (and the service's blanket
     * queue clear) cancels it. Runs during handoff too, so a call starting mid-handoff is seen and blocks
     * reclaim.
     */
    private val callPoll = object : Runnable {
        override fun run() {
            setPausedForCall(audio.inCall)
            reconcile() // also retries a refused/zombie start on the next tick
            scheduler.postDelayed(this, STANDDOWN_POLL_MS)
        }
    }

    /** Seed call state, start the poll, and do the first reconcile. Call once the engine is built. */
    fun start() {
        pausedForCall = audio.inCall // seed call state before the first reconcile
        scheduler.postDelayed(callPoll, STANDDOWN_POLL_MS)
        reconcile()
    }

    /**
     * Fully reset, so the arbiter is left inert and safe to reuse (e.g. in tests). Cancels its own queued
     * posts ([callPoll]/[reclaimConfirm]) and clears both pause reasons and every coordinator.
     * The Android recording-callback is unregistered by [WakeService] (which owns it); this is the pure half of
     * teardown.
     */
    fun detach() {
        scheduler.removeCallbacks(callPoll)
        scheduler.removeCallbacks(reclaimConfirm)
        contention.onCaptureStopped()
        pausedForCall = false
        pausedForHandoff = false
        recovery.reset()
    }

    /**
     * Handle a recording-config change (the body of the old `AudioRecordingCallback`, now fed by
     * [WakeService]'s adapter with just the session ids). Two uses, never mixed:
     *  - **Contention yield** ([contention.active]): we *are* recording — a foreign session id means stand down.
     *  - **Handoff recovery** ([HandoffRecovery.onForeignRecording]): we aren't recording, so any config is the
     *    consumer — act on recovery's [Reclaim] decision when they stop.
     */
    fun onRecordingConfigs(sessionIds: List<Int>) {
        val nowMs = now()

        if (contention.active && contention.onConfigsChanged(sessionIds, nowMs)) {
            DebugLog.log("foreign mic detected (configs=$sessionIds seeded=${contention.seededIds}) → standing down")
            // The consumer is ALREADY recording (that's how we detected it), so arm recovery straight into
            // BORROWED — unlike a wake handoff there's no later "consumer starts" edge to see.
            standDown("foreign mic (detection)", consumerAlreadyRecording = true)
            return
        }

        // We aren't recording while handed off, so any config = the consumer (recovery ignores it when idle).
        when (recovery.onForeignRecording(recording = sessionIds.isNotEmpty())) {
            Reclaim.NONE -> Unit

            Reclaim.NOW -> { // wake handoff — reclaim immediately, keeping "hey jarvis" turns snappy
                DebugLog.log("consumer released mic → reclaiming")
                reclaimNow()
            }

            Reclaim.CONFIRM -> { // detection — may be our own pause's transient empty; see [reclaimConfirm]
                scheduler.removeCallbacks(reclaimConfirm)
                scheduler.postDelayed(reclaimConfirm, RECLAIM_DEBOUNCE_MS)
            }
        }
    }

    /**
     * The single place capture is started or paused. Brings capture in line with the pause reasons via the
     * [CaptureController]: start it when nothing should pause us (only marking capture started on the actual
     * start result, so a refused/zombie start is retried rather than assumed), pause it otherwise.
     *
     * **A voice call always wins.** Immediately before any start (poll, reclaim, retry) the live call state
     * is re-read, closing the up-to-[STANDDOWN_POLL_MS] window between a call starting and the poll seeing
     * it; if a call has begun, the call reason is set and capture stays paused.
     */
    fun reconcile() {
        val want = shouldCapture
        if (want && !controller.isCapturing) {
            if (audio.inCall) {
                setPausedForCall(true, "live check before start")
                return
            }
            if (controller.startCapture()) contention.onCaptureStarted(now())
        } else if (!want && controller.isCapturing) {
            controller.pauseCapture()
            contention.onCaptureStopped()
        }
    }

    /**
     * Pause capture and arm detection-based recovery so a consumer can take the mic. Used for a wake handoff
     * (the consumer is triggered right after, by the service) and for foreign-mic detection (the consumer
     * grabbed it itself). Reclaim is by detection in both cases — no release signal.
     */
    fun standDown(reason: String, consumerAlreadyRecording: Boolean = false) {
        scheduler.removeCallbacks(reclaimConfirm) // a fresh handoff supersedes any pending reclaim
        contention.onCaptureStopped()
        DebugLog.log("WAKE → handing off ($reason)")
        setPausedForHandoff(true)
        // Wake handoff (false): consumer starts AFTER we release → WAITING, and reclaim is immediate.
        // Foreign-mic detection (true): consumer already holds the mic → BORROWED directly, reclaim confirmed.
        recovery.onHandoff(consumerAlreadyRecording)
        reconcile() // pauses capture, freeing the slot
    }

    /** Take the mic back: clear recovery + the handoff pause reason and reconcile (starts unless a call holds). */
    private fun reclaimNow() {
        recovery.reset()
        setPausedForHandoff(false)
        reconcile()
    }

    /** Set/clear the call pause reason, logging the transition (with what triggered it, if not the poll). */
    private fun setPausedForCall(inCall: Boolean, trigger: String? = null) {
        if (pausedForCall == inCall) return
        pausedForCall = inCall
        DebugLog.log("gate: call=$inCall${trigger?.let { " ($it)" } ?: ""} → $status")
    }

    /** Set/clear the handoff pause reason, logging the transition. */
    private fun setPausedForHandoff(handoff: Boolean) {
        if (pausedForHandoff == handoff) return
        pausedForHandoff = handoff
        DebugLog.log("gate: handoff=$handoff → $status")
    }

    companion object {
        // Re-check the in-call state (audio mode) this often (no mode-change event on API 28).
        internal const val STANDDOWN_POLL_MS = 250L

        // After a consumer "release" is seen on the DETECTION path only, confirm against live configs this much
        // later before reclaiming — absorbs the transient empty-config callback emitted while our own capture
        // stops. The wake-handoff path reclaims immediately (no transient-empty race).
        internal const val RECLAIM_DEBOUNCE_MS = 500L
    }
}
