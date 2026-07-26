package com.portal.wake.wake

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Foreign-mic detection while portal-wake is capturing (not during handoff recovery). */
class MicContentionDetectorTest {

    private val seedMs = MicContentionDetector.DEFAULT_SEED_MS

    @Test fun idleNeverDetects() {
        val d = MicContentionDetector(seedMs)
        assertFalse(d.onConfigsChanged(listOf(1), 0))
    }

    @Test fun seeding_collectsOwnSessionIds() {
        val d = MicContentionDetector(seedMs)
        d.onCaptureStarted(0)
        assertFalse(d.onConfigsChanged(listOf(10), 100))
        assertFalse(d.onConfigsChanged(listOf(10), seedMs - 1)) // still seeding
        assertFalse(d.onConfigsChanged(listOf(10), seedMs)) // now tracking, same id — not foreign
    }

    @Test fun seeding_twoConfigsIsContentionImmediately() {
        val d = MicContentionDetector(seedMs)
        d.onCaptureStarted(0)
        assertTrue(d.onConfigsChanged(listOf(10, 20), 50))
    }

    @Test fun seeding_foreignJoiningAfterOwnSeededIsContention() {
        // Our own id seeds first (size 1), then a tap during the seed window adds a second config — the
        // size>=2 rule catches it even before the seed window closes.
        val d = MicContentionDetector(seedMs)
        d.onCaptureStarted(0)
        assertFalse(d.onConfigsChanged(listOf(10), 100)) // ours, seeded
        assertTrue(d.onConfigsChanged(listOf(10, 77), 200)) // foreign joined mid-seed → contention
    }

    @Test fun seeding_singleMicReplacementIsForeign() {
        // The #8 case: this Portal has one mic slot, so a consumer tapping within the seed window REPLACES
        // our config (the list flips [wake] → [consumer], never two at once). Once our own is seeded, a lone
        // unknown id is foreign even mid-seed — we must not adopt it.
        val d = MicContentionDetector(seedMs)
        d.onCaptureStarted(0)
        assertFalse(d.onConfigsChanged(listOf(10), 100)) // wake seeded
        assertTrue(d.onConfigsChanged(listOf(77), 200)) // assistant replaced wake (lone id, within seed)
    }

    @Test fun tracking_detectsNewSessionAfterSeedWindow() {
        val d = MicContentionDetector(seedMs)
        d.onCaptureStarted(0)
        assertFalse(d.onConfigsChanged(listOf(10), 100)) // ours
        assertTrue(d.onConfigsChanged(listOf(10, 99), seedMs + 1)) // foreign joined
    }

    @Test fun tracking_invisibleWake_detectsFirstConfigAfterSeed() {
        val d = MicContentionDetector(seedMs)
        d.onCaptureStarted(0)
        // No callbacks during seed (wake invisible) — first config hours later is foreign.
        assertTrue(d.onConfigsChanged(listOf(42), seedMs + 60_000))
    }

    @Test fun tracking_onlyForeignConfig() {
        val d = MicContentionDetector(seedMs)
        d.onCaptureStarted(0)
        d.onConfigsChanged(emptyList(), seedMs) // cross seed with nothing seen
        assertTrue(d.onConfigsChanged(listOf(7), seedMs + 1))
    }

    @Test fun stopClearsTracking() {
        val d = MicContentionDetector(seedMs)
        d.onCaptureStarted(0)
        d.onCaptureStopped()
        assertFalse(d.onConfigsChanged(listOf(99), seedMs + 1))
    }

    // --- leaked own configs (../../hey-jarvis/PHASE_G.md §37) ---

    @Test fun leakedOwnConfigOnResume_isNotContention() {
        // 🔴 The 2026-07-26 regression, with the real session ids. A call preempted our AudioRecord, the
        // framework never emitted `rec stop` for session 2825, and its config outlived the capture by over
        // an hour. On resume the callback reported BOTH of our ids and the old unconditional `size >= 2`
        // shortcut read that as contention — standing the app down permanently against itself.
        val d = MicContentionDetector(seedMs)
        d.onCaptureStarted(0)
        assertFalse(d.onConfigsChanged(listOf(2825), 100)) // our first capture, seeded
        d.onCaptureStopped() // the call preempts us — and 2825 LEAKS

        d.onCaptureStarted(10_000) // call ends, we resume as session 2873
        assertFalse(d.onConfigsChanged(listOf(2825, 2873), 10_010)) // both ours → must NOT stand down

        // ...and the fresh id is properly seeded, so a genuine foreign grab is still caught.
        assertTrue(d.onConfigsChanged(listOf(2825, 2873, 99), 10_020))
    }

    @Test fun leakedOwnConfigAlone_isNotContention() {
        // The leak can also be the only config left once a consumer stops.
        val d = MicContentionDetector(seedMs)
        d.onCaptureStarted(0)
        assertFalse(d.onConfigsChanged(listOf(2825), 100))
        d.onCaptureStopped()
        d.onCaptureStarted(10_000)
        assertFalse(d.onConfigsChanged(listOf(2825), 10_010))
    }

    @Test fun onlyTheMostRecentCapturesIdsAreRetained() {
        // Retired ids must not accumulate into a blanket excuse — one generation only, so an old id can
        // never mask a real foreign grab later.
        val d = MicContentionDetector(seedMs)
        d.onCaptureStarted(0)
        d.onConfigsChanged(listOf(10), 100) // capture 1 seeds 10
        d.onCaptureStopped()
        d.onCaptureStarted(1_000)
        d.onConfigsChanged(listOf(20), 1_100) // capture 2 seeds 20 (10 is retired)
        d.onCaptureStopped() // now 20 is retired and 10 is FORGOTTEN

        d.onCaptureStarted(2_000)
        assertFalse(d.onConfigsChanged(listOf(20, 30), 2_010)) // 20 retired-ours, 30 seeded as ours
        assertTrue(d.onConfigsChanged(listOf(10), 2_020)) // 10 is no longer excused → foreign
    }

    @Test fun twoUnaccountedIdsAreStillContention() {
        // The `size >= 2` rule survives — it just applies to ids we cannot explain. With a retired id in
        // play, two *unknown* ids still mean we cannot tell which is ours.
        val d = MicContentionDetector(seedMs)
        d.onCaptureStarted(0)
        d.onConfigsChanged(listOf(10), 100)
        d.onCaptureStopped()
        d.onCaptureStarted(1_000)
        assertTrue(d.onConfigsChanged(listOf(10, 55, 66), 1_010))
    }

    @Test fun isOurs_coversSeededAndRetiredOnly() {
        val d = MicContentionDetector(seedMs)
        d.onCaptureStarted(0)
        d.onConfigsChanged(listOf(10), 100)
        assertTrue(d.isOurs(10))
        assertFalse(d.isOurs(99))

        d.onCaptureStopped()
        assertTrue(d.isOurs(10)) // retired, possibly leaked — still ours
        assertFalse(d.isOurs(99))
    }
}
