package com.portal.wake.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.portal.commons.DebugLog
import com.portal.commons.audio.WakeClipRecorder
import com.portal.commons.audio.WakeEvent
import com.portal.commons.audio.WakeMicConfig
import com.portal.commons.audio.WakeMicEngine
import com.portal.commons.audio.WakeModel
import com.portal.wake.mic.MicArbiter
import com.portal.wake.system.Falcon
import com.portal.wake.system.MicLiberator
import com.portal.wake.wake.WakeContract
import com.portal.wake.wake.WakeRegistry
import com.portal.wake.wake.WakeSet
import java.io.File

/**
 * The one always-on component. A `START_STICKY` foreground service (type `microphone`) that hosts the
 * [WakeMicEngine] + recognizer for the life of the device, with **no UI and no launcher** — started on
 * boot by [BootReceiver] (and once at install time by `setup.sh`).
 *
 * Responsibilities are now deliberately narrow — **engine lifecycle + routing only**:
 *  - own the engine lifecycle (build once on start command, refresh the wake set on package changes via a
 *    debounced in-place grammar swap, close on destroy);
 *  - **discover** the [WakeSet] at runtime via [WakeRegistry] and build the recognizer from its words;
 *  - **hand off** a match: look up its wake target and let [WakeHandoff] wake the consumer decided at
 *    discovery (a wake plugin via [WakeContract.ACTION_WAKE], or the native Alexa client via its LISTEN intent).
 *
 * The "should we be holding the mic right now" decision flow — handoff recovery, foreign-mic contention,
 * phone/VoIP call stand-down, and the single reconcile rule that starts/pauses capture — now lives in
 * [MicArbiter]. This service implements [MicArbiter.CaptureController] so the arbiter can apply its
 * decisions to the real engine without owning it. The foreground notification lives in [WakeNotification].
 */
class WakeService :
    Service(),
    MicArbiter.CaptureController {

    private val main = Handler(Looper.getMainLooper())
    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    // The arbiter is Android-free; we hand it thin adapters over the main Handler + AudioManager (below).
    private val arbiter by lazy {
        MicArbiter(AudioManagerGate(audioManager), HandlerScheduler(main), this)
    }

    // Hands a fired wake to its consumer (on main), releasing the mic through the arbiter first.
    private val handoff by lazy { WakeHandoff(this, arbiter) }

    /**
     * The Android recording-config callback: registered on [main] in [onCreate], it forwards the session ids
     * to the (Android-free) [arbiter] — which owns the contention/recovery decision; this is only the Android
     * shell that feeds it. On API 29+ it *also* emits the [logSilencing] diagnostic from the same configs (pure
     * logging, not fed to the arbiter — see [logSilencing]).
     */
    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) {
            val list = configs.orEmpty()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) logSilencing(list)
            arbiter.onRecordingConfigs(list.map { it.clientAudioSessionId })
        }
    }

    // Set once in startListening(), on the first start command; nothing reads them before that. onDestroy guards
    // on ::engine.isInitialized, not `started`, so a throw mid-startListening() can't crash teardown.
    private lateinit var engine: WakeMicEngine
    private lateinit var wakeSet: WakeSet
    private var started = false

    /**
     * The audio behind every wake and near-miss, written to `files/wake-clips/` (see [WakeClipRecorder]), so a false
     * wake can be heard and labelled (`scripts/wake-clips.sh` in portal-apps): its decode alone can't be told from a
     * real one. **Debug builds only**: it records whatever is said near the device, so a release build can't start
     * recording the household by accident, while an evaluation build (`assembleDebug` + `setup.sh`) needs no extra
     * step. Which way it resolved is logged. Set in startListening(), before the engine that uses it.
     */
    private var clips: WakeClipRecorder? = null

    /**
     * Re-discover when wake plugins are installed/removed. A single reinstall delivers 2–3 broadcasts
     * (REMOVED+ADDED+REPLACED), so we *debounce* into one refresh ([scheduleWakeSetRefresh]); the refresh
     * itself ([applyWakeSetChange]) leaves the grammar alone when the wake words are unchanged.
     */
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            DebugLog.log("package change (${intent?.data}) → scheduling wake-set refresh")
            scheduleWakeSetRefresh()
        }
    }

    /** Debounced wake-set refresh; coalesces the 2–3 broadcasts of a single reinstall into one. */
    private val wakeSetRefresh = Runnable { applyWakeSetChange() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        DebugLog.file = File(getExternalFilesDir(null), "debug.txt")
        DebugLog.log("WakeService onCreate")
        audioManager.registerAudioRecordingCallback(recordingCallback, main)
        val pkgFilter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        registerReceiver(packageReceiver, pkgFilter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(WakeNotification.ID, WakeNotification.build(this))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Belt-and-suspenders: start() already refuses on gen2, but guard the service's own entry point too so
            // any future/direct caller that bypasses start() can't strand us holding the OS-silenced mic slot. We
            // still call startForeground above first to honour the startForegroundService contract, then stand down
            // before buildEngine/arbiter ever grab the mic. START_NOT_STICKY so the OS won't relaunch us.
            DebugLog.log("A10+ (gen2): onStartCommand refused — wake is in-app on the assistant; portal-wake inert")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!started) {
            started = true
            startListening()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        DebugLog.log("WakeService onDestroy")
        main.removeCallbacksAndMessages(null) // cancels the arbiter's poll/reclaim + any pending refresh
        runCatching { audioManager.unregisterAudioRecordingCallback(recordingCallback) }
        arbiter.detach()
        runCatching { unregisterReceiver(packageReceiver) }
        if (::engine.isInitialized) engine.close()
        clips?.close()
        // NB: deliberately do NOT DebugLog.close() here. DebugLog is a process-wide singleton shared with
        // BootReceiver and the next service instance; its file path never changes, so onCreate's re-set is a
        // no-op (one reused writer for the process's life, not a per-restart leak). Closing on a per-service
        // onDestroy would instead blank the log across the restart gap — on a device whose only diagnostic
        // channel is debug.txt — and churn the handle. Released at process death.
    }

    // ---- MicArbiter.CaptureController (engine-side seam) --------------------------------------------

    // "Start requested" (see the CaptureController contract); a busy mic comes back as a capture loss.
    override fun startCapture() {
        engine.start()
    }

    override fun pauseCapture() {
        engine.pause()
    }

    // ---- engine lifecycle --------------------------------------------------------------------------

    /**
     * Discover the wake set, build the engine from its words, and start the arbiter (seeds call state, starts
     * the poll, and does the first reconcile). Runs once, on the first start command. The engine is only
     * built here — the arbiter starts it.
     */
    private fun startListening() {
        wakeSet = WakeRegistry.discover(this)
        clips = newClipRecorder()
        engine = WakeMicEngine(
            context = applicationContext,
            config = WakeMicConfig(
                wakeWords = wakeSet.words,
                model = WakeModel.Bundled,
                onUnavailable = { DebugLog.log("wake unavailable (model missing) — service idle") },
                // Runs on the engine's capture thread: only hop to main, where all orchestration runs
                // single-threaded, so the capture thread only captures.
                onWake = { event -> main.post { onWake(event.wakeId) } },
                // On main; the engine logs the cause and never delivers one after close().
                onCaptureLost = arbiter::onCaptureLost,
                beforeMicAcquire = { MicLiberator.freeMic(applicationContext) },
                audit = clips,
            ),
        )
        arbiter.start()
    }

    /** A recorder for [clips] on a debuggable build; null (logged) on a release build. */
    private fun newClipRecorder(): WakeClipRecorder? {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) {
            DebugLog.log("wake clips: off (release build)")
            return null
        }
        val dir = File(getExternalFilesDir(null), CLIPS_DIR)
        DebugLog.log("wake clips → ${dir.absolutePath} (cap ${WakeClipRecorder.DEFAULT_CAP_PER_KIND}/kind)")
        return WakeClipRecorder(dir)
    }

    /**
     * Hand off a fired wake [id] to its target in the current wake set. An id no longer in the set (heard just
     * as the set changed) is logged and ignored, so a stale match never hands the mic to nobody.
     */
    private fun onWake(id: String) {
        val target = wakeSet.target(id)
        if (target == null) {
            DebugLog.log("wake '$id' has no target (ignored)")
            return
        }
        handoff.handOff(target)
    }

    /** Coalesce a burst of package broadcasts (one reinstall = 2–3) into a single debounced refresh. */
    private fun scheduleWakeSetRefresh() {
        main.removeCallbacks(wakeSetRefresh)
        main.postDelayed(wakeSetRefresh, WAKE_SET_REFRESH_DEBOUNCE_MS)
    }

    /**
     * Apply a wake-set change discovered from a package event. We **always** adopt the new set (a wake target's
     * consumer can change even when the words don't), and swap the **grammar in place**
     * ([WakeMicEngine.updateWakeWords]) only when the words changed — no engine teardown, the mic keeps running.
     */
    private fun applyWakeSetChange() {
        if (!started) return // a package change before the first start command: startListening() discovers
        val next = WakeRegistry.discover(this)
        val refresh = wakeSet.refresh(next)
        wakeSet = next
        when (refresh) {
            WakeSet.Refresh.Unchanged -> DebugLog.log("wake words unchanged → consumers refreshed, no grammar rebuild")

            is WakeSet.Refresh.Swap -> {
                DebugLog.log("wake set changed → swapping grammar in place")
                engine.updateWakeWords(refresh.words)
            }
        }
    }

    companion object {
        // A single app reinstall delivers 2–3 package broadcasts (REMOVED+ADDED+REPLACED). Coalesce them into
        // one wake-set refresh so we don't re-discover 2–3× per update.
        private const val WAKE_SET_REFRESH_DEBOUNCE_MS = 1_000L

        // Under the app's external files dir, beside debug.txt. Not the old `clips/`: that holds the openWakeWord-era
        // clips, which would count against these caps.
        private const val CLIPS_DIR = "wake-clips"

        /**
         * Start (or no-op if already running) the always-on wake service.
         *
         * **Gen2 (Android 10, API 29+) self-guard.** On A10 the OS silences a background foreground-service's
         * mic (see [logSilencing]), so this service can only ever hear silence — while still holding the single
         * mic slot and thereby starving the assistant's in-app foreground detector, which owns "hey jarvis" on
         * gen2. So we refuse to start: nothing runs, nothing holds the mic, no notification. On A9 (gen1,
         * API 28) nothing changes — this is the working wake path there. New gen2 setups also skip portal-wake
         * in the portal-apps installer; this guard covers a manual/direct install. `start()` is the single
         * chokepoint (BootReceiver — boot, quick-boot, and the installer's ACTION_START — routes here; the
         * service itself is non-exported).
         */
        fun start(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                DebugLog.file = File(context.getExternalFilesDir(null), "debug.txt")
                DebugLog.log("A10+ (gen2): wake handled in-app by the assistant — portal-wake inert, not starting")
                return
            }
            ContextCompat.startForegroundService(context, Intent(context, WakeService::class.java))
        }
    }
}

/**
 * API 29+ diagnostic for the Android 10 microphone wall. API 29 replaced single-owner mic capture with
 * *concurrent capture + silencing*: a recorder keeps running but its audio is replaced with **silence**
 * ([AudioRecordingConfiguration.isClientSilenced]) rather than being blocked — no read error, no gap, just
 * no wake. On this Portal's Android 10 build a **sideloaded background foreground-service is silenced even
 * as the sole capturer**; only the resumed top Activity records. Confirmed on device via `dumpsys audio`
 * (`pack:com.portal.wake … silenced:true`). Attempts to escape it — holding the assistant role
 * (`VoiceInteractionService`), a `TYPE_APPLICATION_OVERLAY` window, forcing the `RECORD_AUDIO` appop — all
 * failed; the only real fix is a privileged/system install (`CAPTURE_AUDIO_HOTWORD`), blocked on a locked
 * retail unit. So headless background wake is **not supported on Android 10**; this log is what proved that
 * and is kept for future re-investigation (e.g. on an unlocked device).
 *
 * We deliberately do **not** try to identify *which* config is ours: on this Portal the session ids in the
 * callback don't match our `AudioRecord`'s (the same reason [com.portal.wake.mic.MicContentionDetector]
 * seeds rather than compares), so we log the whole picture. Pure logging; it is **not** fed to the arbiter.
 */
@RequiresApi(Build.VERSION_CODES.Q)
private fun logSilencing(configs: List<AudioRecordingConfiguration>) {
    val silenced = configs.filter { it.isClientSilenced }
    if (silenced.isEmpty()) return
    DebugLog.log(
        "silencing: ${silenced.size}/${configs.size} recording client(s) silenced " +
            "(sessions=${silenced.map { it.clientAudioSessionId }}) — concurrent-capture policy active",
    )
}

/** Thin adapter exposing the main [Handler]'s delayed-post API as the arbiter's [MicArbiter.Scheduler]. */
private class HandlerScheduler(private val handler: Handler) : MicArbiter.Scheduler {
    override fun postDelayed(action: Runnable, delayMs: Long) {
        handler.postDelayed(action, delayMs)
    }

    override fun removeCallbacks(action: Runnable) {
        handler.removeCallbacks(action)
    }
}

/** Thin adapter exposing the Android [AudioManager] reads the arbiter needs as [MicArbiter.AudioGate]. */
private class AudioManagerGate(private val audioManager: AudioManager) : MicArbiter.AudioGate {
    // On this Portal a Messenger call drives the mode through MODE_RINGTONE → MODE_IN_CALL →
    // MODE_IN_COMMUNICATION and back to MODE_NORMAL when it ends (verified on device). The mode is
    // device-wide and independent of mic contention, so any non-normal mode = stand down for a call.
    override val inCall: Boolean get() = audioManager.mode != AudioManager.MODE_NORMAL

    // Presence check only (the reclaim confirm asks "is anyone recording?", not who) — no list allocation,
    // and the clientAudioSessionId projection lives in exactly one place: the recordingCallback shell. The list
    // is @NonNull in the SDK (and AOSP builds a fresh one on API 28/29), so isNotEmpty() needs no null check.
    override val hasActiveRecording: Boolean
        get() = audioManager.activeRecordingConfigurations.isNotEmpty()
}
