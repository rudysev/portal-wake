package com.portal.wake.wake

/**
 * Decides **when to reclaim the mic** after portal-wake has handed it off for a wake match — purely from
 * whether the consumer is currently recording. No "done" signal from the consumer, no timer: this is
 * what lets portal-wake recover the mic from *any* consumer (our assistant, the third-party Alexa app)
 * the moment it stops, working even for an uncooperative consumer that could never signal us.
 *
 * Only consulted while portal-wake has paused (handed off), so the "is the consumer recording" input is
 * just "is anyone recording" — we never compare against our own recording.
 *
 * Two ways the hand-off begins (see [onHandoff]):
 *  - **Wake handoff** — the consumer starts *after* we release, so the count is briefly 0; we arm
 *    `WAITING_FOR_CONSUMER` and first wait to *see* it record (which also makes the transient empty while
 *    our own capture stops a no-op), then watch for it to stop.
 *  - **Already recording** — we stood down *because* a foreign app is already holding the mic
 *    (foreign-mic detection); there is no later "consumer starts" edge, so we arm `BORROWED` directly.
 *
 * Pure and deterministic → unit-tested. Drive it from an `AudioManager.AudioRecordingCallback`.
 */
class HandoffRecovery {

    private enum class State { IDLE, WAITING_FOR_CONSUMER, BORROWED }
    private var state = State.IDLE

    /** True while we've handed the mic off and are waiting to get it back. */
    val active: Boolean get() = state != State.IDLE

    /**
     * We released the mic for a hand-off. [consumerAlreadyRecording] = false (the wake-handoff default)
     * waits to see the consumer take the mic; true arms [State.BORROWED] directly, for when we stood down
     * because a foreign app is *already* recording (there's no later "consumer starts" edge to observe).
     */
    fun onHandoff(consumerAlreadyRecording: Boolean = false) {
        state = if (consumerAlreadyRecording) State.BORROWED else State.WAITING_FOR_CONSUMER
    }

    /** We've reacquired (or otherwise reset) — stop tracking. */
    fun reset() {
        state = State.IDLE
    }

    /**
     * Feed whether the consumer is currently recording. Returns true exactly once — when the consumer
     * that held the mic has stopped — meaning portal-wake should reclaim it now.
     */
    fun onForeignRecording(recording: Boolean): Boolean {
        when (state) {
            State.IDLE -> return false

            State.WAITING_FOR_CONSUMER -> if (recording) state = State.BORROWED

            // consumer grabbed it
            State.BORROWED -> if (!recording) {
                state = State.IDLE
                return true
            } // consumer released it
        }
        return false
    }
}
