package com.portal.wake.wake

import com.portal.wake.wake.CaptureGate.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the two-reason capture gate — capture runs only when neither a call nor a handoff is active. The
 * overlap cases (a call arriving mid-handoff) are the point: a single status enum couldn't represent both,
 * so both reasons must be tracked independently and capture resumes only after BOTH clear.
 */
class CaptureGateTest {

    @Test fun startsListening() {
        val g = CaptureGate()
        assertTrue(g.shouldCapture)
        assertEquals(Status.LISTENING, g.status)
    }

    @Test fun eachReasonAlonePausesAndClears() {
        val g = CaptureGate()

        assertTrue(g.onCall(true))
        assertFalse(g.shouldCapture)
        assertEquals(Status.PAUSED_FOR_CALL, g.status)
        assertTrue(g.onCall(false))
        assertTrue(g.shouldCapture)

        assertTrue(g.onHandoff())
        assertFalse(g.shouldCapture)
        assertEquals(Status.PAUSED_FOR_HANDOFF, g.status)
        assertTrue(g.onHandoffEnded())
        assertTrue(g.shouldCapture)
    }

    @Test fun handoffThenCallThenRelease_staysPausedUntilCallClears() {
        val g = CaptureGate()
        g.onHandoff()
        g.onCall(true)
        g.onHandoffEnded() // consumer released, but a call is active
        assertFalse("call still holds capture", g.shouldCapture)
        assertEquals(Status.PAUSED_FOR_CALL, g.status)
        g.onCall(false)
        assertTrue(g.shouldCapture)
    }

    @Test fun handoffThenReleaseWhileCallActive_staysPaused() {
        val g = CaptureGate()
        g.onCall(true) // call active throughout
        g.onHandoff()
        g.onHandoffEnded() // consumer releases, call still active
        assertFalse(g.shouldCapture)
        g.onCall(false)
        assertTrue(g.shouldCapture)
    }

    @Test fun callThenHandoff_bothFlags_resumeOnlyAfterBothClear() {
        val g = CaptureGate()
        g.onCall(true)
        g.onHandoff() // a wake "shouldn't" happen mid-call, but the gate must represent both
        assertFalse(g.shouldCapture)
        g.onHandoffEnded()
        assertFalse("call still holds", g.shouldCapture)
        g.onCall(false)
        assertTrue("both clear → resume", g.shouldCapture)
    }

    @Test fun callClearsWhileHandoffActive_noCapture() {
        val g = CaptureGate()
        g.onHandoff()
        g.onCall(true)
        g.onCall(false) // call clears, but handoff still in flight
        assertFalse(g.shouldCapture)
        assertEquals(Status.PAUSED_FOR_HANDOFF, g.status)
    }

    @Test fun bothClear_shouldCaptureStableAndMutatorsIdempotent() {
        val g = CaptureGate()
        assertTrue(g.shouldCapture)
        assertTrue(g.shouldCapture) // reading twice is stable (reconcile-called-twice → one start)
        assertFalse("redundant clear reports no change", g.onCall(false))
        assertFalse("redundant clear reports no change", g.onHandoffEnded())
        assertTrue(g.shouldCapture)
    }

    @Test fun statusGivesCallPrecedenceWhenBothActive() {
        val g = CaptureGate()
        g.onHandoff()
        g.onCall(true)
        assertEquals(Status.PAUSED_FOR_CALL, g.status)
    }

    @Test fun mutatorsReturnWhetherChanged() {
        val g = CaptureGate()
        assertTrue(g.onCall(true))
        assertFalse(g.onCall(true)) // already in call
        assertTrue(g.onHandoff())
        assertFalse(g.onHandoff()) // already in handoff
        assertTrue(g.onCall(false))
        assertTrue(g.onHandoffEnded())
    }

    @Test fun resetClearsBothReasons() {
        val g = CaptureGate()
        g.onCall(true)
        g.onHandoff()
        g.reset()
        assertTrue(g.shouldCapture)
        assertEquals(Status.LISTENING, g.status)
    }
}
