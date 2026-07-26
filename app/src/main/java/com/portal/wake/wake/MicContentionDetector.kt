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
 *
 * ## 🔴 Leaked own configs, and why "two configs = contention" is not sufficient on its own
 *
 * A config for **our own** capture can outlive that capture. Measured on this Portal 2026-07-26
 * (`../../hey-jarvis/PHASE_G.md` §37): a phone call preempted our `AudioRecord`, the framework never
 * emitted `rec stop` for our session, and the config stayed in `RecordActivityMonitor` **for over an
 * hour, surviving an `am force-stop`**. When the call ended and we resumed, the callback reported *two*
 * ids — **both ours**, one live and one leaked — and the old unconditional `size >= 2` shortcut read that
 * as contention and stood the app down permanently. That is the exact "self-detects" failure the class
 * doc above exists to prevent, arriving through the one path the seeding design did not cover: the
 * shortcut ran *before* seeding and did no ownership check at all.
 *
 * So ownership is now tracked **across** capture cycles: [onCaptureStopped] *retires* the ids it had
 * seeded rather than dropping them, and an id is foreign only when it is neither currently-seeded nor
 * retired. The `size >= 2` rule survives, but it now applies to **unaccounted** ids only — two ids we
 * cannot explain is still contention (we cannot tell which of them is ours), while our own leaked id
 * plus our fresh one is not. Only the most recent capture's ids are retained, so a stale id can never
 * accumulate into a blanket excuse for a genuine foreign grab.
 */
class MicContentionDetector(private val seedMs: Long = DEFAULT_SEED_MS) {

    private enum class Phase { IDLE, SEEDING, TRACKING }
    private var phase = Phase.IDLE
    private var knownSessionIds = emptySet<Int>()

    /**
     * Ids seeded as ours during the **previous** capture, kept because the framework can leave a config
     * behind for a capture that has already stopped (see the class doc). Replaced, never accumulated.
     */
    private var retiredSessionIds = emptySet<Int>()
    private var seedDeadlineMs = 0L

    /** True while we're actively capturing and watching for a foreign recorder. */
    val active: Boolean get() = phase != Phase.IDLE

    /** A snapshot of the session ids seeded as "ours" — for diagnostics/logging. */
    val seededIds: Set<Int> get() = knownSessionIds.toSet()

    /**
     * Whether [sessionId] is one of ours — currently seeded, or retired from the previous capture and
     * possibly leaked. Used by the arbiter so a leaked *own* config is not mistaken for a consumer that
     * is still holding the mic (which would make reclaim unreachable).
     */
    fun isOurs(sessionId: Int): Boolean = sessionId in knownSessionIds || sessionId in retiredSessionIds

    fun onCaptureStarted(nowMs: Long) {
        phase = Phase.SEEDING
        knownSessionIds = emptySet()
        seedDeadlineMs = nowMs + seedMs
    }

    fun onCaptureStopped() {
        phase = Phase.IDLE
        // Retire rather than drop: the framework may keep publishing a config for the capture we just
        // stopped, and on the next start we must recognise it as ours instead of standing down against it.
        if (knownSessionIds.isNotEmpty()) retiredSessionIds = knownSessionIds
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

        // Ids we cannot account for: not seeded as ours this capture, and not left over from the last one.
        val unaccounted = sessionIds.filter { !isOurs(it) }
        if (unaccounted.isEmpty()) return false // all ours (live and/or leaked) → nothing foreign

        // Two at once that we cannot explain = contention in either phase: we can't tell which is ours.
        if (unaccounted.size >= 2) return true

        // Seed our own from the FIRST unaccounted config; until we have, there's nothing to compare against.
        if (phase == Phase.SEEDING && knownSessionIds.isEmpty()) {
            knownSessionIds = setOf(unaccounted.single())
            return false
        }

        // Seeded (or invisible-wake TRACKING with nothing seeded): an id we don't know is a foreign grab —
        // a 2nd recorder OR a single-mic replacement of ours ([wake] → [consumer]).
        return true
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
