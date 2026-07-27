package com.portal.wake.service

import com.portal.commons.DebugLog
import com.portal.wake.audio.CallGate
import com.portal.wake.wake.CaptureGate
import com.portal.wake.wake.HandoffRecovery
import com.portal.wake.wake.MicContentionDetector

/**
 * Owns the **mic-arbitration decision flow** that used to live inline in [WakeService]: it wires the three
 * pure coordinators ([CaptureGate], [HandoffRecovery], [MicContentionDetector]) plus [CallGate] to two
 * injected seams — a [Scheduler] (the controlling thread's delayed posts) and an [AudioGate] (the audio
 * signals it reads) — and drives capture start/pause through the [CaptureController] seam. [WakeService]
 * keeps the engine lifecycle, the match→consumer routing, and the thin Android adapters; this class is the
 * "should we be holding the mic right now" state machine.
 *
 * **No Android framework types.** Everything Android (the `AudioManager` reads, the
 * `AudioRecordingConfiguration` callback, the `Handler`) lives behind [AudioGate]/[Scheduler] in
 * [WakeService]; the arbiter's only non-arbiter dependency is commons' pure-JVM `DebugLog`. So the whole
 * arbiter — including the historically churn-prone reclaim debounce and the detection-vs-handoff branch — is
 * exercised in plain-JVM unit tests with fakes + an injected [now] clock (see `MicArbiterTest`).
 *
 * **Threading.** The seams are driven on one controlling thread (the service's main `Handler` in prod): the
 * recording callback is delivered on it, the poll re-posts to it, and the reclaim debounce is a delayed post
 * on it — so the coordinators are touched single-threaded. The engine's capture thread never calls in here.
 *
 * The single reconcile rule ([reconcile]) is the only place capture is started or paused, so the two pause
 * reasons (call + handoff) can't fight: a call arriving mid-handoff just keeps [CaptureGate.shouldCapture]
 * false until both clear.
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
        /** `AudioManager.getMode()`. */
        val mode: Int

        /** Whether any recording is currently active. A presence check — the reclaim confirm needs "is anyone
         *  recording?", not the ids (those arrive pushed via [onRecordingConfigs]) — so no list is allocated. */
        val hasActiveRecording: Boolean
    }

    private val recovery = HandoffRecovery()
    private val contention = MicContentionDetector()
    private val gate = CaptureGate()

    // current stand-down came from foreign-mic detection → debounce the reclaim
    private var yieldedByDetection = false

    // when that stand-down happened, so [rescueStuckHandoff] can bound how long it may last
    private var stoodDownAtMs = 0L

    /**
     * Confirms a reclaim after [RECLAIM_DEBOUNCE_MS]: only take the mic back if no one is still recording.
     * Absorbs the transient empty-config callback emitted while our own capture stops (which would otherwise
     * reclaim instantly and re-grab the mic from a consumer that never actually released — the foreground-tap
     * race). If a recorder is still present, re-arm recovery and keep waiting.
     */
    private val reclaimConfirm = Runnable {
        if (audio.hasActiveRecording) {
            recovery.onHandoff(consumerAlreadyRecording = true) // still here → BORROWED, await real release
            DebugLog.log("reclaim debounced — consumer still recording")
            return@Runnable
        }
        DebugLog.log("consumer released mic (confirmed) → reclaiming")
        reclaimNow()
    }

    /**
     * The call stand-down poll. API 28 has no audio-mode-change broadcast, so we re-read [AudioGate.mode] on
     * the controlling thread for the service's life. It re-posts itself; [detach] (and the service's blanket
     * queue clear) cancels it. Runs during handoff too, so a call starting mid-handoff is seen and blocks
     * reclaim.
     */
    private val callPoll = object : Runnable {
        override fun run() {
            val inCall = CallGate.inCall(audio.mode)
            if (gate.onCall(inCall)) DebugLog.log("gate: call=$inCall → ${gate.status}")
            rescueStuckHandoff()
            reconcile() // also retries a refused/zombie start on the next tick
            scheduler.postDelayed(this, STANDDOWN_POLL_MS)
        }
    }

    /**
     * **Bounded escape from a stand-down that will never see its release edge.** Reclaim is otherwise driven
     * purely by an *edge* — the config callback reporting that the consumer stopped — and an edge that never
     * arrives leaves the app deaf with no timeout and nothing to recover it.
     *
     * **Both stand-down paths have now done this on real hardware, hours apart** (`PHASE_G.md` §37, §42):
     *  - **detection** — a call leaked a config for our *own* stopped capture, so the list never emptied;
     *  - **wake handoff** — the consumer was handed the mic, hit `no credential → cannot start`, and so
     *    **never recorded at all**. `WAITING_FOR_CONSUMER` has no later edge to wait for. One failed
     *    conversation turned into a permanently deaf device.
     *
     * The rule is the same for both and keys on **"nobody is recording"**, never on elapsed time alone: a
     * turn may be minutes long, and while the consumer holds the mic we must not touch it. Only the timeout
     * differs, because the two paths have different legitimate quiet windows — a detection stand-down means
     * someone was *already* recording, whereas a wake handoff is quiet by construction until the consumer
     * starts, so it gets the longer [WAKE_CONSUMER_TIMEOUT_MS].
     */
    private fun rescueStuckHandoff() {
        if (!recovery.active) return
        val quietFor = now() - stoodDownAtMs
        val limit = if (yieldedByDetection) DETECTION_RECLAIM_GRACE_MS else WAKE_CONSUMER_TIMEOUT_MS
        if (quietFor < limit) return
        if (audio.hasActiveRecording) return // consumer is mid-turn — never steal it
        val why = if (yieldedByDetection) "detection stand-down" else "wake handoff"
        DebugLog.log("$why stuck ${quietFor}ms with no recorder → reclaiming")
        scheduler.removeCallbacks(reclaimConfirm)
        reclaimNow()
    }

    /** Seed call state, start the poll, and do the first reconcile. Call once the engine is built. */
    fun start() {
        gate.onCall(CallGate.inCall(audio.mode)) // seed call state before the first reconcile
        scheduler.postDelayed(callPoll, STANDDOWN_POLL_MS)
        reconcile()
    }

    /**
     * Fully reset, so the arbiter is left inert and safe to reuse (e.g. in tests). Cancels its own queued
     * posts ([callPoll]/[reclaimConfirm]) and clears every coordinator + the debounce flag. The Android
     * recording-callback is unregistered by [WakeService] (which owns it); this is the pure half of teardown.
     */
    fun detach() {
        scheduler.removeCallbacks(callPoll)
        scheduler.removeCallbacks(reclaimConfirm)
        contention.onCaptureStopped()
        gate.reset()
        recovery.reset()
        yieldedByDetection = false
        stoodDownAtMs = 0L
    }

    /**
     * Handle a recording-config change (the body of the old `AudioRecordingCallback`, now fed by
     * [WakeService]'s adapter with just the session ids). Two uses, never mixed:
     *  - **Contention yield** ([contention.active]): we *are* recording — a foreign session id means stand down.
     *  - **Handoff recovery** ([recovery.active]): we aren't recording, so any config is the consumer — reclaim
     *    when they stop.
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

        if (!recovery.active) return // we aren't recording while handed off, so any config = the consumer
        // ...any config that isn't OURS. A config for our own stopped capture can outlive it (a call
        // preempting the mic leaks one — `../../hey-jarvis/PHASE_G.md` §37), and counting that as "the
        // consumer is still recording" makes the release edge — and therefore reclaim — unreachable
        // forever. `MicContentionDetector` already knows which ids are ours; ask it.
        val consumerRecording = sessionIds.any { !contention.isOurs(it) }
        if (recovery.onForeignRecording(consumerRecording)) {
            if (yieldedByDetection) {
                // Detection path only: the consumer was already recording when we stood down, so pausing OUR
                // OWN capture emits a transient empty-config callback that looks like a release and would
                // re-grab the mic instantly. Confirm against live configs after a short debounce.
                scheduler.removeCallbacks(reclaimConfirm)
                scheduler.postDelayed(reclaimConfirm, RECLAIM_DEBOUNCE_MS)
            } else {
                // Wake handoff: the consumer started after we paused (we were BORROWED), so this can only be
                // the real release — reclaim immediately, keeping "hey jarvis" turns snappy.
                DebugLog.log("consumer released mic → reclaiming")
                reclaimNow()
            }
        }
    }

    /**
     * The single place capture is started or paused. Brings capture in line with [CaptureGate] via the
     * [CaptureController]: start it when nothing should pause us (only marking capture started on the actual
     * start result, so a refused/zombie start is retried rather than assumed), pause it otherwise.
     */
    fun reconcile() {
        val want = gate.shouldCapture
        if (want && !controller.isCapturing) {
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
        yieldedByDetection = consumerAlreadyRecording
        stoodDownAtMs = now()
        DebugLog.log("WAKE → handing off ($reason)")
        if (gate.onHandoff()) DebugLog.log("gate: handoff=true → ${gate.status}")
        // Wake handoff (false): consumer starts AFTER we release → WAITING, and reclaim is immediate.
        // Foreign-mic detection (true): consumer already holds the mic → BORROWED directly, reclaim debounced.
        recovery.onHandoff(consumerAlreadyRecording)
        reconcile() // pauses capture, freeing the slot
    }

    /** Take the mic back: clear recovery + the handoff gate reason and reconcile (starts unless a call holds). */
    private fun reclaimNow() {
        yieldedByDetection = false
        recovery.reset()
        if (gate.onHandoffEnded()) DebugLog.log("gate: handoff=false → ${gate.status}")
        reconcile()
    }

    companion object {
        // While a call holds the mic, re-check the audio mode this often (no mode-change event on API 28).
        internal const val STANDDOWN_POLL_MS = 250L

        // After a consumer "release" is seen on the DETECTION path only, confirm against live configs this much
        // later before reclaiming — absorbs the transient empty-config callback emitted while our own capture
        // stops. The wake-handoff path reclaims immediately (no transient-empty race).
        internal const val RECLAIM_DEBOUNCE_MS = 500L

        // Upper bound on a detection stand-down with nobody actually recording: past this, [rescueStuckHandoff]
        // takes the mic back rather than waiting for a release edge that may never arrive. Well clear of
        // [RECLAIM_DEBOUNCE_MS] so it cannot fire on the transient empty while our own capture stops.
        internal const val DETECTION_RECLAIM_GRACE_MS = 5_000L

        // Same bound for a WAKE handoff, but longer: this path is legitimately quiet from the moment we
        // release until the consumer starts recording, so the timeout must clear a consumer cold start with
        // room to spare. Past it, with nothing recording, the consumer never took the mic (it crashed, or
        // refused the turn — a missing API key does exactly this) and waiting forever is strictly worse than
        // listening again. Generous against a warm resident service that normally starts in well under 1 s.
        internal const val WAKE_CONSUMER_TIMEOUT_MS = 10_000L
    }
}
