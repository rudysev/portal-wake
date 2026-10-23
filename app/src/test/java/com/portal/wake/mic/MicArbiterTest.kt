package com.portal.wake.mic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM unit tests for [MicArbiter] — the mic-arbitration wiring that used to be untestable inside
 * `WakeService`. Drives the arbiter through fake [MicArbiter.CaptureController]/[MicArbiter.Scheduler]/
 * [MicArbiter.AudioGate] seams plus a controllable clock, so the historically churn-prone branches
 * (call stand-down, wake-handoff immediate reclaim, detection debounce, capture loss)
 * are locked in.
 */
class MicArbiterTest {

    private class FakeController : MicArbiter.CaptureController {
        var startCount = 0
        var pauseCount = 0

        override fun startCapture() {
            startCount++
        }

        override fun pauseCapture() {
            pauseCount++
        }
    }

    private class FakeScheduler : MicArbiter.Scheduler {
        private val posts = mutableListOf<Pair<Runnable, Long>>()

        override fun postDelayed(action: Runnable, delayMs: Long) {
            posts.add(action to delayMs)
        }

        override fun removeCallbacks(action: Runnable) {
            posts.removeAll { it.first === action }
        }

        fun countWithDelay(delayMs: Long): Int = posts.count { it.second == delayMs }

        /** Remove then run every runnable posted at [delayMs] (a self-re-posting runnable re-arms cleanly). */
        fun fire(delayMs: Long) {
            val due = posts.filter { it.second == delayMs }.map { it.first }
            posts.removeAll { it.second == delayMs }
            due.forEach { it.run() }
        }
    }

    private class FakeAudio : MicArbiter.AudioGate {
        override var inCall: Boolean = false
        override var hasActiveRecording: Boolean = false
    }

    private val ctl = FakeController()
    private val sch = FakeScheduler()
    private val audio = FakeAudio()
    private var clock = 0L
    private val arbiter = MicArbiter(audio, sch, ctl) { clock }

    @Test
    fun start_beginsCaptureWhenIdle_andArmsThePoll() {
        arbiter.start()

        assertTrue(arbiter.isCapturing)
        assertEquals(1, ctl.startCount)
        assertEquals(1, sch.countWithDelay(MicArbiter.STANDDOWN_POLL_MS))
    }

    @Test
    fun start_doesNotCaptureWhileInCall() {
        audio.inCall = true

        arbiter.start()

        assertFalse(arbiter.isCapturing)
        assertEquals(0, ctl.startCount)
    }

    @Test
    fun callPoll_rePostsItselfEachTick() {
        arbiter.start()
        sch.fire(MicArbiter.STANDDOWN_POLL_MS)
        // The poll consumed at this delay re-armed a fresh one.
        assertEquals(1, sch.countWithDelay(MicArbiter.STANDDOWN_POLL_MS))
    }

    @Test
    fun callWhileCapturing_pausesThenResumesWhenCallEnds() {
        arbiter.start()

        audio.inCall = true
        sch.fire(MicArbiter.STANDDOWN_POLL_MS) // poll sees the call → stand down
        assertFalse(arbiter.isCapturing)
        assertEquals(1, ctl.pauseCount)

        audio.inCall = false
        sch.fire(MicArbiter.STANDDOWN_POLL_MS) // call over → capture resumes
        assertTrue(arbiter.isCapturing)
        assertEquals(2, ctl.startCount)
    }

    @Test
    fun wakeHandoff_reclaimsImmediatelyWhenConsumerReleases() {
        arbiter.start()
        arbiter.standDown("wake") // consumerAlreadyRecording = false
        assertFalse(arbiter.isCapturing)
        assertEquals(1, ctl.pauseCount)

        arbiter.onRecordingConfigs(listOf(7)) // consumer takes the mic → BORROWED, no reclaim yet
        assertFalse(arbiter.isCapturing)

        arbiter.onRecordingConfigs(emptyList()) // consumer releases → immediate reclaim (no debounce post)
        assertTrue(arbiter.isCapturing)
        assertEquals(2, ctl.startCount)
        assertEquals(0, sch.countWithDelay(MicArbiter.RECLAIM_DEBOUNCE_MS))
    }

    @Test
    fun detectionStandDown_debouncesTheReclaim() {
        arbiter.start() // capturing; contention seeding from now()=0
        arbiter.onRecordingConfigs(listOf(1)) // seed our own session id (no foreign, no recovery)
        assertTrue(arbiter.isCapturing)

        clock = 100
        arbiter.onRecordingConfigs(listOf(2)) // a foreign id replaced ours → contention stand-down
        assertFalse(arbiter.isCapturing)

        // Consumer (already recording) stops: the detection path must DEBOUNCE, not reclaim immediately.
        audio.hasActiveRecording = false
        arbiter.onRecordingConfigs(emptyList())
        assertFalse(arbiter.isCapturing)
        assertEquals(1, sch.countWithDelay(MicArbiter.RECLAIM_DEBOUNCE_MS))

        // Debounce fires with nobody recording → reclaim now.
        sch.fire(MicArbiter.RECLAIM_DEBOUNCE_MS)
        assertTrue(arbiter.isCapturing)
    }

    @Test
    fun detectionReclaim_reArmsWhenConsumerStillRecordingAtConfirm() {
        arbiter.start()
        arbiter.onRecordingConfigs(listOf(1)) // seed
        clock = 100
        arbiter.onRecordingConfigs(listOf(2)) // foreign → stand down (BORROWED)

        audio.hasActiveRecording = false
        arbiter.onRecordingConfigs(emptyList()) // schedule debounce

        // Consumer is actually still recording when the confirm runs → do NOT reclaim; keep waiting.
        audio.hasActiveRecording = true
        sch.fire(MicArbiter.RECLAIM_DEBOUNCE_MS)
        assertFalse(arbiter.isCapturing)

        // Later it really releases → next confirm reclaims.
        audio.hasActiveRecording = false
        arbiter.onRecordingConfigs(emptyList())
        sch.fire(MicArbiter.RECLAIM_DEBOUNCE_MS)
        assertTrue(arbiter.isCapturing)
    }

    @Test
    fun callMidHandoff_blocksReclaimUntilBothClear() {
        arbiter.start()
        arbiter.standDown("wake") // paused for handoff
        audio.inCall = true
        sch.fire(MicArbiter.STANDDOWN_POLL_MS) // poll sees the call → also paused for call

        arbiter.onRecordingConfigs(listOf(7)) // consumer records
        arbiter.onRecordingConfigs(emptyList()) // consumer releases → handoff reason cleared...
        assertFalse(arbiter.isCapturing) // ...but the call still holds, so no capture

        audio.inCall = false
        sch.fire(MicArbiter.STANDDOWN_POLL_MS) // call clears → reclaim
        assertTrue(arbiter.isCapturing)
    }

    @Test
    fun callEndingMidHandoff_keepsCapturePausedUntilConsumerReleases() {
        arbiter.start()
        arbiter.standDown("wake") // paused for handoff
        arbiter.onRecordingConfigs(listOf(7)) // consumer records

        audio.inCall = true
        sch.fire(MicArbiter.STANDDOWN_POLL_MS) // call begins mid-handoff
        audio.inCall = false
        sch.fire(MicArbiter.STANDDOWN_POLL_MS) // call ends — but the consumer still holds the mic
        assertFalse(arbiter.isCapturing)
        assertEquals(1, ctl.startCount)

        arbiter.onRecordingConfigs(emptyList()) // consumer releases → reclaim
        assertTrue(arbiter.isCapturing)
    }

    @Test
    fun handoffDuringACall_resumesOnlyAfterBothClear() {
        audio.inCall = true
        arbiter.start() // paused for call
        arbiter.standDown("wake") // a handoff while the call holds

        arbiter.onRecordingConfigs(listOf(7)) // consumer records
        arbiter.onRecordingConfigs(emptyList()) // consumer releases → handoff over, call still on
        sch.fire(MicArbiter.STANDDOWN_POLL_MS)
        assertFalse(arbiter.isCapturing)
        assertEquals(0, ctl.startCount)

        audio.inCall = false
        sch.fire(MicArbiter.STANDDOWN_POLL_MS) // call over → capture
        assertTrue(arbiter.isCapturing)
    }

    @Test
    fun reconcile_isIdempotentWhenNothingChanged() {
        arbiter.start()
        arbiter.reconcile()
        arbiter.reconcile()

        assertEquals(1, ctl.startCount)
        assertEquals(0, ctl.pauseCount)
    }

    @Test
    fun reclaim_staysPausedWhenACallBeganSinceTheLastPoll() {
        arbiter.start()
        arbiter.standDown("wake")
        arbiter.onRecordingConfigs(listOf(7)) // consumer records

        audio.inCall = true // call begins between poll ticks — the poll hasn't seen it yet
        arbiter.onRecordingConfigs(emptyList()) // consumer releases → reclaim would start capture
        assertFalse(arbiter.isCapturing)
        assertEquals(1, ctl.startCount) // only the initial start; the reclaim never reached the controller

        sch.fire(MicArbiter.STANDDOWN_POLL_MS) // call still on → still paused
        assertFalse(arbiter.isCapturing)

        audio.inCall = false
        sch.fire(MicArbiter.STANDDOWN_POLL_MS) // call over → capture resumes
        assertTrue(arbiter.isCapturing)
    }

    @Test
    fun retry_staysPausedWhenACallBeganSinceTheLastPoll() {
        arbiter.start()
        arbiter.onCaptureLost() // the mic wouldn't open → reported as a loss, retry pending

        audio.inCall = true // call begins between poll ticks
        arbiter.reconcile() // a retry (e.g. after a wake-set refresh) would now succeed
        assertFalse(arbiter.isCapturing)
        assertEquals(1, ctl.startCount)

        audio.inCall = false
        sch.fire(MicArbiter.STANDDOWN_POLL_MS) // call over → retry takes
        assertTrue(arbiter.isCapturing)
    }

    @Test
    fun notGrantedStart_isMarkedRunning_thenRetriedThroughTheLossPath() {
        arbiter.start()
        assertTrue(arbiter.isCapturing) // marked running as soon as the start is requested
        assertEquals(1, ctl.startCount)

        arbiter.onCaptureLost() // the mic wouldn't open (not granted): a loss, nobody else recording
        assertFalse(arbiter.isCapturing)

        sch.fire(MicArbiter.STANDDOWN_POLL_MS) // poll retries
        assertEquals(2, ctl.startCount)
        assertTrue(arbiter.isCapturing)
    }

    @Test
    fun detach_cancelsPostsAndResetsSoReuseStartsClean() {
        arbiter.start()
        arbiter.standDown("wake") // arms recovery, paused for handoff
        arbiter.detach()

        assertEquals(0, sch.countWithDelay(MicArbiter.STANDDOWN_POLL_MS))
        assertEquals(0, sch.countWithDelay(MicArbiter.RECLAIM_DEBOUNCE_MS))

        // Reused arbiter must start cleanly (pause reasons/recovery cleared), not stuck paused-for-handoff.
        arbiter.start()
        assertTrue(arbiter.isCapturing)
    }

    @Test
    fun detachThenStart_reSeedsCallState() {
        arbiter.start()
        arbiter.standDown("wake") // mid-handoff when the wake set empties
        arbiter.detach() // the service then closes the engine

        audio.inCall = true // a call began while detached
        arbiter.start() // the wake set is non-empty again: engine rebuilt, arbiter started
        assertFalse(arbiter.isCapturing) // call state re-seeded, not stale
        assertEquals(1, sch.countWithDelay(MicArbiter.STANDDOWN_POLL_MS)) // poll re-armed, exactly once

        audio.inCall = false
        sch.fire(MicArbiter.STANDDOWN_POLL_MS) // call over → capture starts (no leftover handoff pause)
        assertTrue(arbiter.isCapturing)
        assertEquals(2, ctl.startCount)
    }

    @Test
    fun standDown_supersedesAPendingReclaimDebounce() {
        arbiter.start()
        arbiter.onRecordingConfigs(listOf(1)) // seed
        clock = 100
        arbiter.onRecordingConfigs(listOf(2)) // foreign → detection stand-down (BORROWED)
        audio.hasActiveRecording = false
        arbiter.onRecordingConfigs(emptyList()) // schedules the debounce
        assertEquals(1, sch.countWithDelay(MicArbiter.RECLAIM_DEBOUNCE_MS))

        // A fresh stand-down must cancel the still-pending reclaim so it can't fire after a new handoff.
        arbiter.standDown("new handoff")
        assertEquals(0, sch.countWithDelay(MicArbiter.RECLAIM_DEBOUNCE_MS))
    }

    @Test
    fun captureLossToAnotherRecorder_waitsBorrowedThenReclaimsAfterConfirmedRelease() {
        arbiter.start()
        audio.hasActiveRecording = true // another app holds the mic (e.g. busy at start-up)
        arbiter.onCaptureLost()
        assertFalse(arbiter.isCapturing)

        sch.fire(MicArbiter.STANDDOWN_POLL_MS) // the poll must NOT retry against the holder
        sch.fire(MicArbiter.STANDDOWN_POLL_MS)
        assertEquals(1, ctl.startCount)

        arbiter.onRecordingConfigs(listOf(9)) // the holder keeps recording → still waiting
        assertFalse(arbiter.isCapturing)

        audio.hasActiveRecording = false
        arbiter.onRecordingConfigs(emptyList()) // holder releases → confirm after the debounce, not now
        assertFalse(arbiter.isCapturing)
        assertEquals(1, sch.countWithDelay(MicArbiter.RECLAIM_DEBOUNCE_MS))

        sch.fire(MicArbiter.RECLAIM_DEBOUNCE_MS) // confirmed → reclaim
        assertTrue(arbiter.isCapturing)
        assertEquals(2, ctl.startCount)
    }

    @Test
    fun captureLossWithNoOtherRecorder_isRetriedOnTheNextPoll() {
        arbiter.start()
        arbiter.onCaptureLost() // a device fault: nobody else is recording
        assertFalse(arbiter.isCapturing)
        assertEquals(1, ctl.startCount)

        sch.fire(MicArbiter.STANDDOWN_POLL_MS) // next tick retries, with no debounce or backoff
        assertTrue(arbiter.isCapturing)
        assertEquals(2, ctl.startCount)
        assertEquals(0, sch.countWithDelay(MicArbiter.RECLAIM_DEBOUNCE_MS))
    }

    @Test
    fun captureLoss_resetsContentionTracking_andTheNextStartReSeeds() {
        arbiter.start()
        arbiter.onRecordingConfigs(listOf(1)) // seed our own session id
        arbiter.onCaptureLost() // device fault → contention stops tracking

        arbiter.onRecordingConfigs(listOf(2)) // while not capturing, a new id is not "foreign mic"
        clock = 1_000 // past the first start's seed window, so only a fresh re-seed accepts the next id
        sch.fire(MicArbiter.STANDDOWN_POLL_MS) // retry starts capture (no stand-down armed)
        assertTrue(arbiter.isCapturing)

        clock = 1_100
        arbiter.onRecordingConfigs(listOf(3)) // re-seeded: our fresh session is ours, not foreign
        assertTrue(arbiter.isCapturing)

        arbiter.onRecordingConfigs(listOf(4)) // an unknown id after the re-seed → contention stand-down
        assertFalse(arbiter.isCapturing)
    }

    @Test
    fun deviceFaultRetry_staysPausedDuringACall() {
        arbiter.start()
        arbiter.onCaptureLost() // device fault → retry pending

        audio.inCall = true // call begins between poll ticks
        arbiter.reconcile() // a retry before the poll sees the call
        assertFalse(arbiter.isCapturing)
        sch.fire(MicArbiter.STANDDOWN_POLL_MS) // the poll's retry during the call
        assertFalse(arbiter.isCapturing)
        assertEquals(1, ctl.startCount)

        audio.inCall = false
        sch.fire(MicArbiter.STANDDOWN_POLL_MS) // call over → retry takes
        assertTrue(arbiter.isCapturing)
        assertEquals(2, ctl.startCount)
    }

    @Test
    fun captureLoss_isAuthoritative_soTheEngineIsNotPausedAgain() {
        arbiter.start()
        audio.hasActiveRecording = true
        arbiter.onCaptureLost() // the engine is already idle after a loss

        assertFalse(arbiter.isCapturing)
        assertEquals(0, ctl.pauseCount)
    }
}
