package com.portal.wake.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.portal.commons.DebugLog
import com.portal.commons.audio.OpenWakeWordDetector
import com.portal.commons.audio.WakeDetectors
import com.portal.commons.audio.WakeEvent
import com.portal.commons.audio.WakeMicConfig
import com.portal.commons.audio.WakeMicEngine
import com.portal.wake.system.Falcon
import com.portal.wake.system.MicLiberator
import com.portal.wake.wake.WakeContract
import com.portal.wake.wake.WakeRegistry
import com.portal.wake.wake.WakeRouting
import com.portal.wake.wake.WakeTarget
import java.io.File

/**
 * The one always-on component. A `START_STICKY` foreground service (type `microphone`) that hosts the
 * [WakeMicEngine] + recognizer for the life of the device, with **no UI and no launcher** — started on
 * boot by [BootReceiver] (and once at install time by `setup.sh`).
 *
 * Responsibilities are now deliberately narrow — **engine lifecycle + routing only**:
 *  - own the engine lifecycle (start on command, refresh the wake set on package changes via a debounced
 *    in-place grammar swap, stop on destroy);
 *  - **discover** wake targets at runtime via [WakeRegistry] and build the recognizer from them;
 *  - **hand off** a match: trigger the consumer (a registered handler app via [WakeContract.ACTION_WAKE],
 *    or the native Alexa app via its LISTEN intent for "alexa").
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

    /**
     * The Android recording-config callback: registered on [main] in [onCreate], it forwards the session ids
     * to the (Android-free) [arbiter] — which owns the contention/recovery decision; this is only the Android
     * shell that feeds it. On API 29+ it *also* emits the [logSilencing] diagnostic from the same configs (pure
     * logging, not fed to the arbiter — see [logSilencing]).
     */
    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) {
            val list = configs ?: emptyList()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) logSilencing(list)
            arbiter.onRecordingConfigs(list.map { it.clientAudioSessionId })
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
     * callback don't match our `AudioRecord`'s (the same reason [com.portal.wake.wake.MicContentionDetector]
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

    private var engine: WakeMicEngine? = null
    private var targets: List<WakeTarget> = emptyList()
    private var started = false

    // Routing state for the oww-is-default split, resolved from the wake set (not per-fire — see [onWake]).
    private var owwActive = false
    private var owwOwnedId: String? = null

    // Whether the current engine is started (reflects what we last applied). The arbiter drives this via
    // startCapture/pauseCapture, but onError/onStopped also clear it out-of-band (guarded by engineGeneration)
    // so the next reconcile retries — the arbiter's view can lag by up to one poll tick by design.
    private var capturing = false
    private var engineGeneration = 0 // main-only; bumped per built engine to ignore a replaced engine's onStopped

    /**
     * Re-discover when wake plugins are installed/removed. A single reinstall delivers 2–3 broadcasts
     * (REMOVED+ADDED+REPLACED), so we *debounce* into one refresh ([scheduleWakeSetRefresh]); the refresh
     * itself ([applyWakeSetChange]) no-ops when the discovered wake set is unchanged.
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
            buildEngine()
            arbiter.start() // seeds call state, starts the poll, and does the first reconcile
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
        engine?.close()
        engine = null
        capturing = false
        // NB: deliberately do NOT DebugLog.close() here. DebugLog is a process-wide singleton shared with
        // BootReceiver and the next service instance; its file path never changes, so onCreate's re-set is a
        // no-op (one reused writer for the process's life, not a per-restart leak). Closing on a per-service
        // onDestroy would instead blank the log across the restart gap — on a device whose only diagnostic
        // channel is debug.txt — and churn the handle. Released at process death.
    }

    // ---- MicArbiter.CaptureController (engine-side seam) --------------------------------------------

    override fun startCapture(): Boolean {
        val ok = engine?.start() ?: false
        capturing = ok
        return ok
    }

    override fun pauseCapture() {
        engine?.pause()
        capturing = false
    }

    override val isCapturing: Boolean get() = capturing

    // ---- engine lifecycle --------------------------------------------------------------------------

    /** (Re)create the engine instance from the discovered wake set. Does NOT start it — the arbiter does. */
    private fun buildEngine() {
        targets = WakeRegistry.discover(this)
        val words = WakeRegistry.wakeWords(targets)
        if (words.isEmpty()) {
            DebugLog.log("no wake words to listen for")
            engine = null
            return
        }
        owwActive = OpenWakeWordDetector.assetsPresent(applicationContext)
        owwOwnedId = if (owwActive) OpenWakeWordDetector.ownedWakeId(words) else null
        val generation = ++engineGeneration
        engine = WakeMicEngine(
            context = applicationContext,
            config = WakeMicConfig(
                wakeWords = words,
                detectors = detectorFactories(),
                onDetectorUnavailable = { id -> DebugLog.log("wake unavailable ($id)") },
                onWake = ::onWake,
                onError = { message ->
                    DebugLog.log("engine error: $message")
                    if (engineGeneration == generation) capturing = false
                },
                onStopped = { if (engineGeneration == generation) capturing = false },
                beforeMicAcquire = { MicLiberator.freeMic(applicationContext) },
            ),
        )
    }

    /**
     * The detector set. **openWakeWord is the default** for "hey jarvis" when its bundled assets are present.
     * **Vosk always runs**: it routes every other discovered word and is the jarvis fallback when oww is absent.
     */
    private fun detectorFactories() = buildList {
        add(WakeDetectors.vosk())
        if (OpenWakeWordDetector.assetsPresent(applicationContext)) {
            DebugLog.log("openWakeWord assets present → oww is the DEFAULT detector for jarvis (Vosk routes other words)")
            add(WakeDetectors.oww())
        } else {
            DebugLog.log("openWakeWord assets absent → Vosk routes all words")
        }
    }

    /** Coalesce a burst of package broadcasts (one reinstall = 2–3) into a single debounced refresh. */
    private fun scheduleWakeSetRefresh() {
        main.removeCallbacks(wakeSetRefresh)
        main.postDelayed(wakeSetRefresh, WAKE_SET_REFRESH_DEBOUNCE_MS)
    }

    /**
     * Apply a wake-set change discovered from a package event. We **always** refresh [targets] (routing can
     * change even when the words don't), then skip the expensive grammar/engine work when the words are
     * unchanged. A genuine word change swaps the **grammar in place** ([WakeMicEngine.updateWakeWords]) — no
     * engine teardown, mic keeps running. Only the empty↔non-empty transitions build or stop the engine.
     */
    private fun applyWakeSetChange() {
        val newTargets = WakeRegistry.discover(this)
        val newWords = WakeRegistry.wakeWords(newTargets)
        val wordsUnchanged = WakeRegistry.sameWakeSet(WakeRegistry.wakeWords(targets), newWords)
        targets = newTargets // ALWAYS refresh routing — the component for an unchanged word may have changed
        owwOwnedId = if (owwActive) OpenWakeWordDetector.ownedWakeId(newWords) else null
        if (wordsUnchanged) {
            DebugLog.log("wake words unchanged → routing refreshed, no grammar rebuild")
            return
        }
        when {
            // Defensive/unreachable: WakeRegistry.discover() always returns at least the built-in jarvis
            // default, so the wake set is never empty in practice. If that ever changes, note this path stops
            // the engine outside the arbiter (capturing=false directly) and so does NOT reset the arbiter's
            // gate/contention — route it through the arbiter before relying on an empty wake set.
            newWords.isEmpty() -> {
                DebugLog.log("wake set now empty → stopping engine")
                engine?.close()
                engine = null
                capturing = false
            }

            engine == null -> {
                DebugLog.log("wake set now non-empty → building engine")
                buildEngine()
                arbiter.reconcile()
            }

            else -> {
                DebugLog.log("wake set changed → swapping grammar in place")
                engine?.updateWakeWords(newWords)
            }
        }
    }

    // ---- match dispatch ----------------------------------------------------------------------------

    /**
     * Called by the engine on its capture thread when a wake fires. Routes via [WakeRouting] so oww-owned
     * jarvis fires don't double-handoff through Vosk, then hops to the main thread for dispatch.
     */
    private fun onWake(event: WakeEvent) {
        if (!WakeRouting.shouldRoute(event.detectorId, event.wakeId, owwActive, owwOwnedId)) return
        main.post { dispatch(event.wakeId) }
    }

    private fun dispatch(id: String) {
        val target = targets.firstOrNull { it.word.id == id }
        if (target == null) {
            DebugLog.log("wake '$id' has no target (ignored)")
            return
        }
        val component = target.component
        when {
            id == ROUTE_ALEXA -> handOff("alexa → falcon LISTEN") {
                sendBroadcast(
                    Intent(Falcon.LISTEN_ACTION)
                        .setPackage(Falcon.PACKAGE)
                        .addFlags(HANDOFF_FLAGS),
                )
            }

            component != null -> handOff("$id → ${target.source}") {
                sendBroadcast(
                    Intent(WakeContract.ACTION_WAKE)
                        .setComponent(component)
                        .putExtra(WakeContract.EXTRA_WAKE_ID, id)
                        .addFlags(HANDOFF_FLAGS),
                )
            }

            else -> DebugLog.log("WAKE '$id' (no handler installed)")
        }
    }

    /**
     * Release the mic so the consumer can take it (via the arbiter), trigger the consumer ([send]), and let
     * the arbiter recover by detection. Order is deliberate: the mic must be **free before the consumer
     * starts**, so [MicArbiter.standDown] pauses capture before we broadcast.
     */
    private inline fun handOff(what: String, send: () -> Unit) {
        arbiter.standDown(what)
        runCatching { send() }.onFailure { DebugLog.log("handoff trigger failed: ${it.message}") }
    }

    companion object {
        // A single app reinstall delivers 2–3 package broadcasts (REMOVED+ADDED+REPLACED). Coalesce them into
        // one wake-set refresh so we don't re-discover 2–3× per update.
        private const val WAKE_SET_REFRESH_DEBOUNCE_MS = 1_000L

        // Built-in "hey alexa" convenience route: trigger the Portal's native Alexa client directly (see
        // [Falcon]). Third-party handlers use the ACTION_WAKE contract instead of being hard-coded here.
        private const val ROUTE_ALEXA = "alexa"

        // Handoff broadcast flags. INCLUDE_STOPPED_PACKAGES so a force-stopped consumer still receives it;
        // RECEIVER_FOREGROUND so it goes through Android's foreground broadcast queue (the background queue
        // adds noticeable latency to a cold consumer's "hey jarvis" → assistant-ready path).
        private const val HANDOFF_FLAGS =
            Intent.FLAG_INCLUDE_STOPPED_PACKAGES or Intent.FLAG_RECEIVER_FOREGROUND

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
    override val mode: Int get() = audioManager.mode

    // Presence check only (the reclaim confirm asks "is anyone recording?", not who) — no list allocation,
    // and the clientAudioSessionId projection lives in exactly one place: the recordingCallback shell.
    override val hasActiveRecording: Boolean
        get() = !audioManager.activeRecordingConfigurations.isNullOrEmpty()
}
