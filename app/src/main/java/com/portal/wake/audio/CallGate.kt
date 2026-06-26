package com.portal.wake.audio

/**
 * Decides, from the device audio mode, whether a phone/VoIP call is active — so portal-wake can fully
 * **stand down** (release the mic and stop re-grabbing) for the call instead of fighting it.
 *
 * On this Portal a Messenger call drives `AudioManager.getMode()` through `MODE_RINGTONE` →
 * `MODE_IN_CALL` → `MODE_IN_COMMUNICATION` and back to `MODE_NORMAL` when it ends (verified on device).
 * The mode is device-wide and independent of mic contention, which makes it a far more reliable signal
 * than the recording-config list that even Meta's own millennium relies on (and still churns with).
 *
 * Pure (takes the raw mode int) so it is unit-tested with no Android dependency.
 */
object CallGate {

    /** AudioManager.MODE_NORMAL — the only mode in which no call is ringing/active. */
    private const val MODE_NORMAL = 0

    /** True while the device is ringing or in a call (any non-normal audio mode). */
    fun inCall(audioMode: Int): Boolean = audioMode != MODE_NORMAL
}
