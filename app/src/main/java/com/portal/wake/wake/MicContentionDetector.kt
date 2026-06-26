package com.portal.wake.wake

/**
 * Detects when **another app** grabs the mic while portal-wake is actively capturing — without comparing
 * against our own `AudioRecord.getAudioSessionId()` (on this Portal that id does not reliably match the
 * session id reported in `AudioRecordingConfiguration`, which caused a yield-to-self loop when we tried
 * that approach).
 *
 * Instead: we seed the **first** recording config we see after capture starts as ours; after that, any
 * session id we haven't seen — whether it joins as a 2nd config OR **replaces** ours (this is a single-mic
 * device, so contention usually shows up as the id flipping `[wake] → [consumer]`, not two coexisting) — is
 * foreign and we should stand down. The [seedMs] window only covers the invisible-wake case where no config
 * callback arrives until after it. Drive it from `AudioManager.AudioRecordingCallback` while capturing.
 */
class MicContentionDetector(private val seedMs: Long = DEFAULT_SEED_MS) {

    private enum class Phase { IDLE, SEEDING, TRACKING }
    private var phase = Phase.IDLE
    private var knownSessionIds = emptySet<Int>()
    private var seedDeadlineMs = 0L

    /** True while we're actively capturing and watching for a foreign recorder. */
    val active: Boolean get() = phase != Phase.IDLE

    /** A snapshot of the session ids seeded as "ours" — for diagnostics/logging. */
    val seededIds: Set<Int> get() = knownSessionIds.toSet()

    fun onCaptureStarted(nowMs: Long) {
        phase = Phase.SEEDING
        knownSessionIds = emptySet()
        seedDeadlineMs = nowMs + seedMs
    }

    fun onCaptureStopped() {
        phase = Phase.IDLE
        knownSessionIds = emptySet()
    }

    /**
     * @param sessionIds `AudioRecordingConfiguration.clientAudioSessionId` for each active recording.
     * @return true when a foreign recorder appeared and portal-wake should stand down.
     */
    fun onConfigsChanged(sessionIds: List<Int>, nowMs: Long): Boolean {
        if (phase == Phase.IDLE) return false

        if (phase == Phase.SEEDING && nowMs >= seedDeadlineMs) {
            phase = Phase.TRACKING
        }

        if (sessionIds.size >= 2) return true // two recorders at once = contention (either phase)

        // Seed our own from the FIRST non-empty config; until we have, there's nothing to compare against.
        if (phase == Phase.SEEDING && knownSessionIds.isEmpty()) {
            if (sessionIds.isNotEmpty()) knownSessionIds = sessionIds.toSet()
            return false
        }

        // Seeded (or invisible-wake TRACKING with nothing seeded): any id we don't know is a foreign grab —
        // a 2nd recorder OR a single-mic replacement of ours ([wake] → [consumer]). Empty list → not foreign.
        return sessionIds.any { it !in knownSessionIds }
    }

    companion object {
        /**
         * Grace after capture starts during which we wait for our **own** first config to seed (we only seed
         * the first non-empty config, then any unknown id is foreign). NOT "adopt every id for 750ms" — a
         * consumer appearing after our seed, even within this window, is detected. It only matters when no
         * config arrives before the deadline (invisible wake): we then enter TRACKING and the first late
         * config is treated as foreign.
         */
        const val DEFAULT_SEED_MS = 750L
    }
}
