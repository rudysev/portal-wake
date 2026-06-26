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
}
