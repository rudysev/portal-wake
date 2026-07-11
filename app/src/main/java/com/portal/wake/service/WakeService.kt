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
import com.portal.commons.audio.WakeDetectors
import com.portal.commons.audio.WakeEvent
import com.portal.commons.audio.WakeMicConfig
import com.portal.commons.audio.WakeMicEngine
import com.portal.wake.system.Falcon
import com.portal.wake.system.MicLiberator
import com.portal.wake.wake.OwwHeadResolver
import com.portal.wake.wake.WakeContract
import com.portal.wake.wake.WakeRegistry
import com.portal.wake.wake.WakeSetChange
import com.portal.wake.wake.WakeSetEngineAction
import com.portal.wake.wake.WakeTarget
import java.io.File

/**
 * The one always-on component. A `START_STICKY` foreground service (type `microphone`) that hosts the
 * [WakeMicEngine] + openWakeWord detector for the life of the device, with **no UI and no launcher** —
 * started on boot by [BootReceiver] (and once at install time by `setup.sh`).
 */
class WakeService :
    Service(),
    MicArbiter.CaptureController {

    private val main = Handler(Looper.getMainLooper())
    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    private val arbiter by lazy {
        MicArbiter(AudioManagerGate(audioManager), HandlerScheduler(main), this)
    }

    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) {
            val list = configs ?: emptyList()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) logSilencing(list)
            arbiter.onRecordingConfigs(list.map { it.clientAudioSessionId })
        }
    }

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
    private var capturing = false
    private var engineGeneration = 0

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            DebugLog.log("package change (${intent?.data}) → scheduling wake-set refresh")
            scheduleWakeSetRefresh()
        }
    }

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
            DebugLog.log("A10+ (gen2): onStartCommand refused — wake is in-app on the assistant; portal-wake inert")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!started) {
            started = true
            buildEngine()
            arbiter.start()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        DebugLog.log("WakeService onDestroy")
        main.removeCallbacksAndMessages(null)
        runCatching { audioManager.unregisterAudioRecordingCallback(recordingCallback) }
        arbiter.detach()
        runCatching { unregisterReceiver(packageReceiver) }
        engine?.close()
        engine = null
        capturing = false
    }

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

    private fun buildEngine() {
        targets = WakeRegistry.discover(this)
        val heads = OwwHeadResolver.resolve(applicationContext, targets)
        if (heads.isEmpty()) {
            DebugLog.log("no wake words with oww models to listen for")
            engine = null
            return
        }
        val words = WakeRegistry.wakeWords(targets)
        val generation = ++engineGeneration
        engine = WakeMicEngine(
            context = applicationContext,
            config = WakeMicConfig(
                wakeWords = words,
                detectors = listOf(WakeDetectors.oww(heads)),
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

    private fun scheduleWakeSetRefresh() {
        main.removeCallbacks(wakeSetRefresh)
        main.postDelayed(wakeSetRefresh, WAKE_SET_REFRESH_DEBOUNCE_MS)
    }

    private fun applyWakeSetChange() {
        val newTargets = WakeRegistry.discover(this)
        val detectableUnchanged = WakeRegistry.sameDetectableSet(targets, newTargets)
        targets = newTargets // ALWAYS refresh routing — the component for an unchanged word may have changed
        val heads = if (detectableUnchanged) emptyList() else OwwHeadResolver.resolve(applicationContext, newTargets)
        val action = WakeSetChange.decide(
            detectableUnchanged = detectableUnchanged,
            hasDetectableHeads = heads.isNotEmpty(),
            hasEngine = engine != null,
        )
        when (action) {
            WakeSetEngineAction.ROUTING_ONLY ->
                DebugLog.log("wake words unchanged → routing refreshed, no detector rebuild")

            WakeSetEngineAction.STOP -> {
                DebugLog.log("wake set now has no detectable words → stopping engine")
                engine?.close()
                engine = null
            }

            WakeSetEngineAction.BUILD -> {
                DebugLog.log("wake set now non-empty → building engine")
                buildEngine()
            }

            WakeSetEngineAction.REBUILD -> {
                DebugLog.log("wake set changed → rebuilding engine")
                engine?.close()
                // close()'s onStopped is ignored once buildEngine bumps engineGeneration — clear
                // capturing via mustClearCapturing before reconcile or the mic never restarts.
                buildEngine()
            }
        }
        if (WakeSetChange.mustClearCapturing(action)) capturing = false
        if (action == WakeSetEngineAction.BUILD || action == WakeSetEngineAction.REBUILD) {
            arbiter.reconcile()
        }
    }

    private fun onWake(event: WakeEvent) {
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

    private inline fun handOff(what: String, send: () -> Unit) {
        arbiter.standDown(what)
        runCatching { send() }.onFailure { DebugLog.log("handoff trigger failed: ${it.message}") }
    }

    companion object {
        private const val WAKE_SET_REFRESH_DEBOUNCE_MS = 1_000L
        private const val ROUTE_ALEXA = "alexa"
        private const val HANDOFF_FLAGS =
            Intent.FLAG_INCLUDE_STOPPED_PACKAGES or Intent.FLAG_RECEIVER_FOREGROUND

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

private class HandlerScheduler(private val handler: Handler) : MicArbiter.Scheduler {
    override fun postDelayed(action: Runnable, delayMs: Long) {
        handler.postDelayed(action, delayMs)
    }

    override fun removeCallbacks(action: Runnable) {
        handler.removeCallbacks(action)
    }
}

private class AudioManagerGate(private val audioManager: AudioManager) : MicArbiter.AudioGate {
    override val mode: Int get() = audioManager.mode
    override val hasActiveRecording: Boolean
        get() = !audioManager.activeRecordingConfigurations.isNullOrEmpty()
}
