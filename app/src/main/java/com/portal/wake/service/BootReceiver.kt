package com.portal.wake.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.portal.commons.DebugLog

/**
 * Starts the always-on [WakeService] after the device boots, so wake-listening comes up on its own with
 * **nothing on screen** (this app has no Activity and no launcher icon — it is headless by design).
 *
 * On the Portal (Android 9 / API 28) a background-started foreground microphone service is allowed, so
 * the service can acquire the mic straight from boot. (This would not be reliable on Android 12+.)
 *
 * Caveat — the "stopped state": Android won't deliver BOOT_COMPLETED to an app that has never had a
 * component started since install. With no launcher there is no tap to do that, so `setup.sh` kicks the
 * app once right after install via [ACTION_START] (the [WakeService] itself stays private/non-exported —
 * this exported receiver is the single controlled entry point). From then on boot start-up is automatic.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED, ACTION_QUICKBOOT_POWERON, ACTION_START -> {
                DebugLog.log("BootReceiver: ${intent.action} → starting WakeService")
                WakeService.start(context)
            }
        }
    }

    private companion object {
        // Some OEMs send this instead of (or alongside) BOOT_COMPLETED on a "quick boot".
        const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"

        // Explicit "start now" trigger used by setup.sh to launch the headless service after install
        // (there is no launcher to tap). A normal app can't start our private service from the shell.
        // NB: the manifest intent-filter + setup.sh repeat this literal — they can't reference a const.
        const val ACTION_START = "com.portal.wake.action.START"
    }
}
