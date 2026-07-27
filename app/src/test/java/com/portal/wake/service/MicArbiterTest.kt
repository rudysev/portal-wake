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
        override var mode: Int = MODE_NORMAL
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
        audio.mode = MODE_IN_CALL

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
        audio.mode = MODE_IN_CALL
        sch.fire(MicArbiter.STANDDOWN_POLL_MS) // poll sees the call → also paused for call

        arbiter.onRecordingConfigs(listOf(7)) // consumer records
        arbiter.onRecordingConfigs(emptyList()) // consumer releases → handoff reason cleared...
        assertFalse(ctl.isCapturing) // ...but the call still holds, so no capture

        audio.mode = MODE_NORMAL
        sch.fire(MicArbiter.STANDDOWN_POLL_MS) // call clears → reclaim
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

        // Reused arbiter must start cleanly (gate/recovery cleared), not stuck paused-for-handoff.
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

    // --- the post-call deafness regression (../../hey-jarvis/PHASE_G.md §37) ---

    @Test
    fun postCall_resumeWithLeakedOwnConfig_keepsListening() {
        // 🔴 Measured on device 2026-07-26 and it cost ~1.1 h of task C collection. A call preempts our
        // capture; the framework never emits `rec stop`, so the config for our own stopped session leaks;
        // on resume the callback reports [leaked-ours, fresh-ours] and the app stood down against itself —
        // permanently, because reclaim then waited on an empty list that could never arrive.
        arbiter.start()
        arbiter.onRecordingConfigs(listOf(2825)) // our capture, seeded

        audio.mode = MODE_IN_CALL
        sch.fire(MicArbiter.STANDDOWN_POLL_MS) // call takes the mic; 2825 LEAKS
        assertFalse(ctl.isCapturing)

        clock = 90_000
        audio.mode = MODE_NORMAL
        sch.fire(MicArbiter.STANDDOWN_POLL_MS) // call ends → we resume as session 2873
        assertTrue(ctl.isCapturing)

        arbiter.onRecordingConfigs(listOf(2825, 2873)) // both ours → must keep listening
        assertTrue(ctl.isCapturing)
    }

    @Test
    fun borrowedWithALeakedOwnConfig_stillReclaims() {
        // Reclaim used to require an EMPTY config list. A leaked config for our own stopped capture never
        // goes away, so the release edge was unreachable and the app stayed deaf even after the consumer
        // had finished.
        arbiter.start()
        arbiter.onRecordingConfigs(listOf(2825)) // seed ours
        clock = 100
        arbiter.onRecordingConfigs(listOf(77)) // a real consumer replaced us → detection stand-down
        assertFalse(ctl.isCapturing)

        // The consumer stops, but OUR leaked config is still published — the list never empties.
        audio.hasActiveRecording = false
        arbiter.onRecordingConfigs(listOf(2825))
        assertEquals(1, sch.countWithDelay(MicArbiter.RECLAIM_DEBOUNCE_MS))

        sch.fire(MicArbiter.RECLAIM_DEBOUNCE_MS)
        assertTrue(ctl.isCapturing)
    }

    @Test
    fun stuckDetectionStandDown_isRescuedAfterTheGrace() {
        // Defence in depth for the *class* of the bug: a release edge that simply never arrives. Bounded,
        // and only when nothing at all is recording.
        arbiter.start()
        arbiter.onRecordingConfigs(listOf(1))
        clock = 100
        arbiter.onRecordingConfigs(listOf(2)) // detection stand-down
        assertFalse(ctl.isCapturing)

        audio.hasActiveRecording = false // nobody is recording, and no further callback ever comes
        clock = 100 + MicArbiter.DETECTION_RECLAIM_GRACE_MS - 1
        sch.fire(MicArbiter.STANDDOWN_POLL_MS)
        assertFalse(ctl.isCapturing) // still inside the grace — must not re-grab yet

        clock = 100 + MicArbiter.DETECTION_RECLAIM_GRACE_MS
        sch.fire(MicArbiter.STANDDOWN_POLL_MS)
        assertTrue(ctl.isCapturing)
    }

    @Test
    fun rescueNeverStealsTheMicFromALiveConsumer() {
        // A turn can be minutes long. The escape must key on "nobody is recording", never on elapsed time.
        arbiter.start()
        arbiter.onRecordingConfigs(listOf(1))
        clock = 100
        arbiter.onRecordingConfigs(listOf(2)) // detection stand-down
        audio.hasActiveRecording = true // consumer genuinely holds the mic

        clock = 100 + MicArbiter.DETECTION_RECLAIM_GRACE_MS * 10
        sch.fire(MicArbiter.STANDDOWN_POLL_MS)
        assertFalse(ctl.isCapturing)
    }

    @Test
    fun wakeHandoff_consumerThatNeverRecords_isRescued() {
        // 🔴 Observed on device 2026-07-26 (PHASE_G.md §42). The wake fired and handed off; the consumer
        // hit "no credential → cannot start" and never opened the mic. WAITING_FOR_CONSUMER has no later
        // edge to wait for, so one failed conversation left the device permanently deaf.
        arbiter.start()
        arbiter.standDown("wake") // consumerAlreadyRecording = false
        assertFalse(ctl.isCapturing)
        audio.hasActiveRecording = false // the consumer never records

        clock = MicArbiter.WAKE_CONSUMER_TIMEOUT_MS - 1
        sch.fire(MicArbiter.STANDDOWN_POLL_MS)
        assertFalse(ctl.isCapturing) // still inside the window a consumer may legitimately take to start

        clock = MicArbiter.WAKE_CONSUMER_TIMEOUT_MS
        sch.fire(MicArbiter.STANDDOWN_POLL_MS)
        assertTrue(ctl.isCapturing)
    }

    @Test
    fun wakeHandoff_rescueNeverInterruptsALiveTurn() {
        // A turn may be minutes long. The escape keys on "nobody is recording", never on elapsed time.
        arbiter.start()
        arbiter.standDown("wake")
        arbiter.onRecordingConfigs(listOf(7)) // consumer took the mic → BORROWED
        audio.hasActiveRecording = true

        clock = MicArbiter.WAKE_CONSUMER_TIMEOUT_MS * 20
        sch.fire(MicArbiter.STANDDOWN_POLL_MS)
        assertFalse(ctl.isCapturing)

        // And the normal edge still reclaims immediately — the snappy path is untouched.
        audio.hasActiveRecording = false
        arbiter.onRecordingConfigs(emptyList())
        assertTrue(ctl.isCapturing)
    }

    @Test
    fun wakeHandoff_rescueDoesNotPreemptAPromptConsumer() {
        // The consumer starts well inside the timeout, as a warm resident service does. Nothing fires.
        arbiter.start()
        arbiter.standDown("wake")

        clock = 800
        arbiter.onRecordingConfigs(listOf(7)) // consumer up in 0.8 s
        audio.hasActiveRecording = true
        clock = MicArbiter.WAKE_CONSUMER_TIMEOUT_MS + 1
        sch.fire(MicArbiter.STANDDOWN_POLL_MS)
        assertFalse(ctl.isCapturing)
    }

    private companion object {
        const val MODE_NORMAL = 0
        const val MODE_IN_CALL = 2 // AudioManager.MODE_IN_CALL — any non-normal mode reads as in-call
    }
}
