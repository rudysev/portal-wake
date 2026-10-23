package com.portal.wake.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM unit tests for [MicArbiter] — the mic-arbitration wiring that used to be untestable inside
 * `WakeService`. Drives the arbiter through fake [MicArbiter.CaptureController]/[MicArbiter.Scheduler]/
 * [MicArbiter.AudioGate] seams plus a controllable clock, so the historically churn-prone branches
 * (call stand-down, wake-handoff immediate reclaim, detection debounce, refused-start retry) are locked in.
 */
class MicArbiterTest {

    private class FakeController : MicArbiter.CaptureController {
        var startResult = true
        var startCount = 0
        var pauseCount = 0
        private var capturingFlag = false

        override fun startCapture(): Boolean {
            startCount++
            capturingFlag = startResult
            return capturingFlag
        }

        override fun pauseCapture() {
            pauseCount++
            capturingFlag = false
        }

        override val isCapturing: Boolean get() = capturingFlag
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

        assertTrue(ctl.isCapturing)
        assertEquals(1, ctl.startCount)
        assertEquals(1, sch.countWithDelay(MicArbiter.STANDDOWN_POLL_MS))
    }

    @Test
    fun start_doesNotCaptureWhileInCall() {
        audio.inCall = true

        arbiter.start()

        assertFalse(ctl.isCapturing)
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
        assertFalse(ctl.isCapturing)
        assertEquals(1, ctl.pauseCount)

        audio.inCall = false
        sch.fire(MicArbiter.STANDDOWN_POLL_MS) // call over → capture resumes
        assertTrue(ctl.isCapturing)
        assertEquals(2, ctl.startCount)
    }

    @Test
    fun wakeHandoff_reclaimsImmediatelyWhenConsumerReleases() {
        arbiter.start()
        arbiter.standDown("wake") // consumerAlreadyRecording = false
        assertFalse(ctl.isCapturing)
        assertEquals(1, ctl.pauseCount)

        arbiter.onRecordingConfigs(listOf(7)) // consumer takes the mic → BORROWED, no reclaim yet
        assertFalse(ctl.isCapturing)

        arbiter.onRecordingConfigs(emptyList()) // consumer releases → immediate reclaim (no debounce post)
        assertTrue(ctl.isCapturing)
        assertEquals(2, ctl.startCount)
        assertEquals(0, sch.countWithDelay(MicArbiter.RECLAIM_DEBOUNCE_MS))
    }

    @Test
    fun detectionStandDown_debouncesTheReclaim() {
        arbiter.start() // capturing; contention seeding from now()=0
        arbiter.onRecordingConfigs(listOf(1)) // seed our own session id (no foreign, no recovery)
        assertTrue(ctl.isCapturing)

        clock = 100
        arbiter.onRecordingConfigs(listOf(2)) // a foreign id replaced ours → contention stand-down
        assertFalse(ctl.isCapturing)

        // Consumer (already recording) stops: the detection path must DEBOUNCE, not reclaim immediately.
        audio.hasActiveRecording = false
        arbiter.onRecordingConfigs(emptyList())
        assertFalse(ctl.isCapturing)
        assertEquals(1, sch.countWithDelay(MicArbiter.RECLAIM_DEBOUNCE_MS))

        // Debounce fires with nobody recording → reclaim now.
        sch.fire(MicArbiter.RECLAIM_DEBOUNCE_MS)
        assertTrue(ctl.isCapturing)
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
        assertFalse(ctl.isCapturing)

        // Later it really releases → next confirm reclaims.
        audio.hasActiveRecording = false
        arbiter.onRecordingConfigs(emptyList())
        sch.fire(MicArbiter.RECLAIM_DEBOUNCE_MS)
        assertTrue(ctl.isCapturing)
    }

    @Test
    fun callMidHandoff_blocksReclaimUntilBothClear() {
        arbiter.start()
        arbiter.standDown("wake") // paused for handoff
        audio.inCall = true
        sch.fire(MicArbiter.STANDDOWN_POLL_MS) // poll sees the call → also paused for call

        arbiter.onRecordingConfigs(listOf(7)) // consumer records
        arbiter.onRecordingConfigs(emptyList()) // consumer releases → handoff reason cleared...
        assertFalse(ctl.isCapturing) // ...but the call still holds, so no capture

        audio.inCall = false
        sch.fire(MicArbiter.STANDDOWN_POLL_MS) // call clears → reclaim
        assertTrue(ctl.isCapturing)
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
        assertFalse(ctl.isCapturing)
        assertEquals(1, ctl.startCount)

        arbiter.onRecordingConfigs(emptyList()) // consumer releases → reclaim
        assertTrue(ctl.isCapturing)
    }

    @Test
    fun handoffDuringACall_resumesOnlyAfterBothClear() {
        audio.inCall = true
        arbiter.start() // paused for call
        arbiter.standDown("wake") // a handoff while the call holds

        arbiter.onRecordingConfigs(listOf(7)) // consumer records
        arbiter.onRecordingConfigs(emptyList()) // consumer releases → handoff over, call still on
        sch.fire(MicArbiter.STANDDOWN_POLL_MS)
        assertFalse(ctl.isCapturing)
        assertEquals(0, ctl.startCount)

        audio.inCall = false
        sch.fire(MicArbiter.STANDDOWN_POLL_MS) // call over → capture
        assertTrue(ctl.isCapturing)
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
        assertFalse(ctl.isCapturing)
        assertEquals(1, ctl.startCount) // only the initial start; the reclaim never reached the controller

        sch.fire(MicArbiter.STANDDOWN_POLL_MS) // call still on → still paused
        assertFalse(ctl.isCapturing)

        audio.inCall = false
        sch.fire(MicArbiter.STANDDOWN_POLL_MS) // call over → capture resumes
        assertTrue(ctl.isCapturing)
    }

    @Test
    fun retry_staysPausedWhenACallBeganSinceTheLastPoll() {
        ctl.startResult = false
        arbiter.start() // refused → not capturing, retry pending
        ctl.startResult = true

        audio.inCall = true // call begins between poll ticks
        arbiter.reconcile() // a retry (e.g. after a wake-set refresh) would now succeed
        assertFalse(ctl.isCapturing)
        assertEquals(1, ctl.startCount)

        audio.inCall = false
        sch.fire(MicArbiter.STANDDOWN_POLL_MS) // call over → retry takes
        assertTrue(ctl.isCapturing)
    }

    @Test
    fun reconcile_retriesWhenStartCaptureRefused() {
        ctl.startResult = false
        arbiter.start() // refused
        assertFalse(ctl.isCapturing)
        assertEquals(1, ctl.startCount)

        sch.fire(MicArbiter.STANDDOWN_POLL_MS) // poll retries
        assertEquals(2, ctl.startCount)
        assertFalse(ctl.isCapturing)

        ctl.startResult = true
        sch.fire(MicArbiter.STANDDOWN_POLL_MS) // now it takes
        assertEquals(3, ctl.startCount)
        assertTrue(ctl.isCapturing)
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
        assertTrue(ctl.isCapturing)
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
}
