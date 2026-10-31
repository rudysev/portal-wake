package com.portal.wake.hybrid

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.os.Handler
import androidx.core.content.ContextCompat
import com.portal.commons.DebugLog
import com.portal.commons.audio.HybridWakeConfig
import java.io.File

/**
 * What `WakeService` needs for the hybrid wake detector: the [settings] that pick the detector, the [evidence] log
 * (`files/hybrid/`, beside debug.txt), the engine's [config], and the adb-only controls. Built once listening starts.
 *
 * The controls are a receiver registered only for senders holding `DUMP`, which `adb shell` has and an installed app
 * can't get: [ACTION_SET_DETECTOR] switches the detector (`--es detector hybrid|vosk`, `--ez shadow true|false`) and
 * [ACTION_MARK_MISS] runs [markMiss] (the engine saves the last 30 s as a missed wake). See README "Hybrid wake
 * detector (prototype)".
 */
class HybridSetup(private val context: Context, handler: Handler, private val markMiss: () -> Unit) {

    val settings = DetectorSettings(context)

    /**
     * Audio clips only on a debuggable build: they are whatever is said near the device, so a release build can't start
     * recording the household by accident. Which way it resolved is logged, and the session's detector opens the log.
     */
    val evidence: EvidenceRecorder = run {
        val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val dir = File(context.getExternalFilesDir(null), EVIDENCE_DIR)
        DebugLog.log("detector: ${settings.choice}; evidence → $dir (audio clips ${if (debuggable) "on" else "off"})")
        EvidenceRecorder(dir, keepAudio = debuggable).also {
            it.note("session", System.currentTimeMillis(), mapOf("detector" to settings.choice.toString()))
        }
    }

    private val controls = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            when (intent?.action) {
                ACTION_SET_DETECTOR -> setDetector(intent)
                ACTION_MARK_MISS -> this@HybridSetup.markMiss.invoke()
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(ACTION_SET_DETECTOR)
            addAction(ACTION_MARK_MISS)
        }
        ContextCompat.registerReceiver(
            context,
            controls,
            filter,
            Manifest.permission.DUMP,
            handler,
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    /** The engine's hybrid: decides [WAKE_ID] with the APK's models, live on [settings], recording to [evidence]. */
    fun config() = HybridWakeConfig(
        wakeId = WAKE_ID,
        newGate = { OpenWakeWordGate.fromAssets(context.assets) },
        newSpotter = { SherpaSpotter.fromAssets(context.assets) },
        mode = settings::mode,
        shadow = settings::shadow,
        onEvidence = evidence::record,
    )

    /** Unregister the controls and stop the evidence writer. */
    fun close() {
        runCatching { context.unregisterReceiver(controls) }
        evidence.close()
    }

    /** [ACTION_SET_DETECTOR]: apply and persist the change; the engine picks it up at the next frame. */
    private fun setDetector(intent: Intent) {
        val shadow = if (intent.hasExtra(EXTRA_SHADOW)) intent.getBooleanExtra(EXTRA_SHADOW, true) else null
        val line = settings.change(intent.getStringExtra(EXTRA_DETECTOR), shadow)
        DebugLog.log(line)
        evidence.note("detector", System.currentTimeMillis(), mapOf("change" to line))
    }

    companion object {
        /** The wake word the hybrid decides: its gate and spotter are trained on "hey jarvis". */
        const val WAKE_ID = "jarvis"

        const val EVIDENCE_DIR = "hybrid"

        const val ACTION_SET_DETECTOR = "com.portal.wake.action.SET_DETECTOR"
        const val EXTRA_DETECTOR = "detector"
        const val EXTRA_SHADOW = "shadow"
        const val ACTION_MARK_MISS = "com.portal.wake.action.MARK_MISS"
    }
}
