package com.portal.wake.wake

/**
 * Pure two-reason coordinator for whether the wake engine should be capturing. There are two **independent**
 * pause reasons — an active phone/VoIP **call** and an in-flight **handoff** (the mic handed to a consumer) —
 * and they can hold at the same time (a call can start mid-handoff). Capture runs only when [shouldCapture]
 * (neither reason holds).
 *
 * No Android here: `WakeService` feeds it events (a periodic audio-mode poll for calls; handoff start/end)
 * and reconciles the engine to [shouldCapture]. Mirrors [HandoffRecovery] / `CallGate` as unit-tested
 * decision logic. Mutators return whether a flag actually changed, so the caller can log per-reason
 * transitions.
 */
class CaptureGate {
    private var pausedForCall = false
    private var pausedForHandoff = false

    /** True only when no pause reason is active — the engine should be capturing. */
    val shouldCapture: Boolean get() = !pausedForCall && !pausedForHandoff

    /** Derived label for logging; **call takes precedence** when both reasons hold. */
    val status: Status
        get() = when {
            pausedForCall -> Status.PAUSED_FOR_CALL
            pausedForHandoff -> Status.PAUSED_FOR_HANDOFF
            else -> Status.LISTENING
        }

    /** Set/clear the call reason (driven by the service's audio-mode poll). Returns true if it changed. */
    fun onCall(inCall: Boolean): Boolean {
        if (pausedForCall == inCall) return false
        pausedForCall = inCall
        return true
    }

    /** Enter handoff (mic yielded to a consumer). Returns true if it changed. */
    fun onHandoff(): Boolean {
        if (pausedForHandoff) return false
        pausedForHandoff = true
        return true
    }

    /** Leave handoff (the consumer released the mic). Returns true if it changed. */
    fun onHandoffEnded(): Boolean {
        if (!pausedForHandoff) return false
        pausedForHandoff = false
        return true
    }

    /** Clear both reasons (engine rebuild / service teardown). */
    fun reset() {
        pausedForCall = false
        pausedForHandoff = false
    }

    enum class Status { LISTENING, PAUSED_FOR_CALL, PAUSED_FOR_HANDOFF }
}
