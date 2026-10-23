package com.portal.wake.wake

/**
 * Decides **when to reclaim the mic** after portal-wake has handed it off — purely from whether the consumer
 * is currently recording. No "done" signal from the consumer, no timer: this is what lets portal-wake
 * recover the mic from *any* consumer (our assistant, the third-party Alexa app) the moment it stops,
 * working even for an uncooperative consumer that could never signal us.
 *
 * Only consulted while portal-wake has paused (handed off), so the "is the consumer recording" input is
 * just "is anyone recording" — we never compare against our own recording.
 *
 * Two ways the hand-off begins (see [onHandoff]), remembered so the release can be judged:
 *  - **Wake handoff** — the consumer starts *after* we release, so the count is briefly 0; we arm
 *    `WAITING_FOR_CONSUMER` and first wait to *see* it record (which also makes the transient empty while
 *    our own capture stops a no-op), then watch for it to stop → [Reclaim.NOW].
 *  - **Detection** — we stood down *because* a foreign app is already holding the mic (foreign-mic
 *    detection); there is no later "consumer starts" edge, so we arm `BORROWED` directly. A release here
 *    may be the transient empty from pausing our own capture → [Reclaim.CONFIRM]: the caller re-checks after
 *    a debounce and reports back via [onReleaseUnconfirmed] if a recorder is still present.
 *
 * Pure and deterministic → unit-tested. Drive it from an `AudioManager.AudioRecordingCallback`.
 */
class HandoffRecovery {

    /** What the caller should do after a foreign-recording change. */
    enum class Reclaim {
        /** Keep waiting. */
        NONE,

        /** The consumer released after a wake handoff — reclaim immediately. */
        NOW,

        /** A release after a detection stand-down — re-check live recordings after the debounce, then
         *  reclaim, or call [onReleaseUnconfirmed] if a recorder is still present. */
        CONFIRM,
    }

    private enum class State { IDLE, WAITING_FOR_CONSUMER, BORROWED, CONFIRMING }
    private var state = State.IDLE

    // recovery began from foreign-mic detection (not a wake handoff) → a release must be confirmed
    private var fromDetection = false

    /** True while we've handed the mic off and are waiting to get it back. */
    val active: Boolean get() = state != State.IDLE

    /**
     * We released the mic for a hand-off. [consumerAlreadyRecording] = false (the wake-handoff default)
     * waits to see the consumer take the mic; true arms [State.BORROWED] directly, for when we stood down
     * because a foreign app is *already* recording (there's no later "consumer starts" edge to observe).
     */
    fun onHandoff(consumerAlreadyRecording: Boolean = false) {
        state = if (consumerAlreadyRecording) State.BORROWED else State.WAITING_FOR_CONSUMER
        fromDetection = consumerAlreadyRecording
    }

    /** We've reacquired (or otherwise reset) — stop tracking. */
    fun reset() {
        state = State.IDLE
    }

    /**
     * Feed whether the consumer is currently recording. Returns [Reclaim.NOW] or [Reclaim.CONFIRM] exactly
     * once per release — when the consumer that held the mic has stopped — and [Reclaim.NONE] otherwise
     * (including while a [Reclaim.CONFIRM] is outstanding).
     */
    fun onForeignRecording(recording: Boolean): Reclaim {
        when (state) {
            State.IDLE, State.CONFIRMING -> return Reclaim.NONE

            State.WAITING_FOR_CONSUMER -> if (recording) state = State.BORROWED

            // consumer grabbed it
            State.BORROWED -> if (!recording) {
                if (fromDetection) {
                    state = State.CONFIRMING
                    return Reclaim.CONFIRM
                }
                state = State.IDLE
                return Reclaim.NOW
            } // consumer released it
        }
        return Reclaim.NONE
    }

    /** The [Reclaim.CONFIRM] re-check found a recorder still present — back to borrowed, await the real release. */
    fun onReleaseUnconfirmed() {
        if (state == State.CONFIRMING) state = State.BORROWED
    }
}
