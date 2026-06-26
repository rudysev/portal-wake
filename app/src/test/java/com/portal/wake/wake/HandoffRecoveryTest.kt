package com.portal.wake.wake

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The recovery rule: reclaim the mic only once the consumer that held it has stopped — never on the
 * transient "no one recording" right after our own release during a hand-off.
 */
class HandoffRecoveryTest {

    @Test fun idleNeverReclaims() {
        val r = HandoffRecovery()
        assertFalse(r.onForeignRecording(false))
        assertFalse(r.onForeignRecording(true))
        assertFalse(r.active)
    }

    // ---- explicit hand-off (we released first; consumer grabs later) -------------------------------

    @Test fun handoff_doesNotReclaimOnReleaseRaceBeforeConsumerGrabs() {
        val r = HandoffRecovery()
        r.onHandoff()
        assertFalse(r.onForeignRecording(false)) // our own release — no foreigner yet; must NOT reclaim
        assertFalse(r.onForeignRecording(false))
        assertTrue(r.active)
    }

    @Test fun handoff_reclaimsOnceConsumerGrabsThenReleases() {
        val r = HandoffRecovery()
        r.onHandoff()
        assertFalse(r.onForeignRecording(false)) // waiting
        assertFalse(r.onForeignRecording(true)) // consumer grabbed → borrowed
        assertFalse(r.onForeignRecording(true)) // still borrowed
        assertTrue(r.onForeignRecording(false)) // consumer released → reclaim
        assertFalse(r.active)
    }

    @Test fun handoff_reclaimsExactlyOnce() {
        val r = HandoffRecovery()
        r.onHandoff()
        r.onForeignRecording(true)
        assertTrue(r.onForeignRecording(false)) // reclaim
        assertFalse(r.onForeignRecording(false)) // already idle
    }

    @Test fun resetStopsTracking() {
        val r = HandoffRecovery()
        r.onHandoff()
        r.onForeignRecording(true)
        r.reset()
        assertFalse(r.active)
        assertFalse(r.onForeignRecording(false))
    }

    // ---- foreign-mic detection (consumer already recording at stand-down) ---------------------------

    @Test fun alreadyRecording_armsBorrowedAndReclaimsOnRelease() {
        // No "consumer starts" edge to see — the foreign app already holds the mic, so we arm BORROWED and
        // reclaim on the first real release.
        val r = HandoffRecovery()
        r.onHandoff(consumerAlreadyRecording = true)
        assertTrue(r.active)
        assertFalse(r.onForeignRecording(true)) // still recording → stay borrowed
        assertTrue(r.onForeignRecording(false)) // released → reclaim
        assertFalse(r.active)
    }

    @Test fun alreadyRecording_debounceReArmSequence() {
        // The WakeService debounce: a transient empty fires a reclaim (BORROWED→IDLE), the confirm sees the
        // consumer still recording and re-arms BORROWED; the real release then reclaims.
        val r = HandoffRecovery()
        r.onHandoff(consumerAlreadyRecording = true)
        assertTrue(r.onForeignRecording(false)) // transient empty → would reclaim…
        r.onHandoff(consumerAlreadyRecording = true) // …but confirm re-arms BORROWED (consumer still there)
        assertTrue(r.active)
        assertTrue(r.onForeignRecording(false)) // real release → reclaim
        assertFalse(r.active)
    }
}
