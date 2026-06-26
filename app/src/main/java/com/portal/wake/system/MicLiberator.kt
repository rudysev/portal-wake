package com.portal.wake.system

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import com.portal.commons.DebugLog

/**
 * Frees the microphone from the Portal's "Hey Alexa" wake detector (`com.millennium`) so this app can
 * own the single handset-mic slot.
 *
 * `com.millennium` runs an always-on Vosk "Hey Alexa" wake listener on the **handset mic** — the same
 * slot we need — and starves us while it runs (it drives `falcon`, the Alexa client). We best-effort
 * stop it when we acquire the mic.
 *
 * We deliberately do **NOT** touch `falcon` (the Amazon Alexa app). Verified on device across every
 * config: falcon — alive or dead, foreground or background — does NOT contend for our handset-mic
 * capture ("hey jarvis" *and* "hey alexa" both decode at 1.00 with falcon running). Killing it would
 * gain us nothing and would remove the client a future "hey alexa" hand-off needs (broadcast falcon's
 * LISTEN intent to invoke Alexa). So falcon is left running.
 *
 * Stopping a system service from a normal app is best-effort: [ActivityManager.killBackgroundProcesses]
 * only reaches a target the OS ranks as a *cached/background* process — it is a **no-op against a
 * foreground service**, which an always-on wake listener almost certainly is (it holds a notification,
 * hidden by the Portal's custom UI). So in practice `setup.sh`'s one-time `pm disable-user com.millennium`
 * does the real work; this call is just a cheap, harmless best-effort on the acquire path. (A normal app
 * also **cannot** force-stop/disable another — `am force-stop` exits 255.) The target package set is
 * resolved **once and cached** — `getInstalledPackages()` is a heavy PackageManager IPC and the installed
 * set doesn't change at runtime, so scanning on every mic (re)acquire would only stutter the caller.
 */
object MicLiberator {

    private const val TAG = "PortalWakeMicLiberator"

    // Confirmed on a Portal+ (model "aloha"): millennium = com.millennium (the "Hey Alexa" wake
    // detector, the handset-mic competitor). Matched by codename so we don't depend on the exact id; we
    // must NOT touch the ~50 other com.facebook.aloha.* system packages, so we match only this codename.
    private val CODENAMES = listOf("millennium")

    /** Explicit confirmed package id, used even if the scan below doesn't surface it. */
    private val KNOWN_PACKAGES = listOf(
        "com.millennium",
    )

    // Resolved once on the first call and cached (see class doc — the PackageManager scan is heavy and
    // the installed set is static at runtime).
    @Volatile private var cachedTargets: List<String>? = null

    /**
     * Best-effort: stop the wake-word services so the mic is available. Safe to call repeatedly and from
     * the main thread — the package scan is cached after the first call, leaving only a lightweight
     * [killBackgroundProcesses] binder IPC. Never throws.
     */
    fun freeMic(context: Context) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        val targets = targets(context)
        if (targets.isEmpty()) {
            Log.i(TAG, "no wake-word packages found to stop")
            return
        }
        for (pkg in targets) {
            val killed = runCatching { am.killBackgroundProcesses(pkg) }.isSuccess
            DebugLog.log("freeMic pkg=$pkg killBg=$killed")
        }
    }

    /** Installed packages matching [CODENAMES], plus [KNOWN_PACKAGES] — scanned once, then cached. */
    private fun targets(context: Context): List<String> {
        cachedTargets?.let { return it }
        val scanned = runCatching {
            val pm = context.packageManager
            @Suppress("DEPRECATION", "QueryPermissionsNeeded")
            pm.getInstalledPackages(0)
                .map { it.packageName }
                .filter { name -> CODENAMES.any { name.contains(it, ignoreCase = true) } }
        }.getOrElse {
            Log.w(TAG, "package scan failed: ${it.message}")
            emptyList()
        }
        return (scanned + KNOWN_PACKAGES).distinct().also { cachedTargets = it }
    }
}
