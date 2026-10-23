package com.portal.wake.mic

import com.portal.wake.mic.HandoffRecovery.Reclaim
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reclaim rule: take the mic back only once the consumer that held it has stopped — never on the
 * transient "no one recording" right after our own release. A wake handoff reclaims [Reclaim.NOW]; a
 * detection stand-down asks the caller to [Reclaim.CONFIRM] against live configs first.
 */
class HandoffRecoveryTest {

    @Test fun idleNeverReclaims() {
        val r = HandoffRecovery()
        assertEquals(Reclaim.NONE, r.onForeignRecording(false))
        assertEquals(Reclaim.NONE, r.onForeignRecording(true))
        assertFalse(r.active)
    }

    @Test fun resetStopsTracking_evenWithAConfirmOutstanding() {
        val r = HandoffRecovery()
        r.onHandoff(consumerAlreadyRecording = true)
        assertEquals(Reclaim.CONFIRM, r.onForeignRecording(false))
        r.reset() // confirmed → reclaimed
        assertFalse(r.active)
        r.onReleaseUnconfirmed() // a stale confirm can't re-arm an idle recovery
        assertEquals(Reclaim.NONE, r.onForeignRecording(true))
        assertEquals(Reclaim.NONE, r.onForeignRecording(false))
        assertFalse(r.active)
    }

    // ---- wake handoff (we released first; consumer grabs later) -------------------------------------

    @Test fun wakeHandoff_noneUntilConsumerRecords_thenNowOnRelease() {
        val r = HandoffRecovery()
        r.onHandoff()
        assertEquals(Reclaim.NONE, r.onForeignRecording(false)) // our own release — no consumer yet
        assertEquals(Reclaim.NONE, r.onForeignRecording(false))
        assertEquals(Reclaim.NONE, r.onForeignRecording(true)) // consumer grabbed → borrowed
        assertEquals(Reclaim.NONE, r.onForeignRecording(true)) // still borrowed
        assertEquals(Reclaim.NOW, r.onForeignRecording(false)) // consumer released → reclaim now
        assertFalse(r.active)
        assertEquals(Reclaim.NONE, r.onForeignRecording(false)) // exactly once
    }

    // ---- detection stand-down (consumer already recording when we stood down) -----------------------

    @Test fun detection_confirmsOnRelease() {
        // No "consumer starts" edge to see — the foreign app already holds the mic. A release may be the
        // transient empty from pausing our own capture, so the caller must confirm before reclaiming.
        val r = HandoffRecovery()
        r.onHandoff(consumerAlreadyRecording = true)
        assertTrue(r.active)
        assertEquals(Reclaim.NONE, r.onForeignRecording(true)) // still recording → stay borrowed
        assertEquals(Reclaim.CONFIRM, r.onForeignRecording(false)) // released → confirm
        assertEquals(Reclaim.NONE, r.onForeignRecording(false)) // exactly once while the confirm is outstanding
        assertEquals(Reclaim.NONE, r.onForeignRecording(true))
        assertTrue(r.active)
    }

    @Test fun detection_releaseUnconfirmed_returnsToBorrowed_thenConfirmsAgain() {
        val r = HandoffRecovery()
        r.onHandoff(consumerAlreadyRecording = true)
        assertEquals(Reclaim.CONFIRM, r.onForeignRecording(false)) // transient empty → confirm
        r.onReleaseUnconfirmed() // the confirm still found a recorder
        assertTrue(r.active)
        assertEquals(Reclaim.NONE, r.onForeignRecording(true)) // still borrowed
        assertEquals(Reclaim.CONFIRM, r.onForeignRecording(false)) // real release → confirm again
    }
}
