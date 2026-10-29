package com.portal.wake.wake

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Bundle
import com.portal.commons.DebugLog
import com.portal.wake.system.Falcon

/**
 * Reads the [WakeSet] from Android — **what to listen for and where to route it** — by *discovering* wake
 * plugins at runtime and checking whether the native Alexa client is installed. The set's own rules (built-in
 * defaults, a plugin always winning over a default, never empty) live in [WakeSet]; this object only reads.
 *
 * Discovery (the extensible path): every app that responds to [WakeContract.ACTION_WAKE] and declares a
 * [WakeContract.META_PHRASE] (plus optional id/min-confidence) meta-data is a wake plugin. We build its
 * wake word from its wake declaration ([WakeDeclaration]) and a [Consumer.Plugin] naming its receiver. Adding
 * "hey X" is therefore a manifest entry in *that* app — no change here.
 *
 * The Portal's Alexa stack is `com.millennium` (the "Hey Alexa" Vosk detector, on the handset mic) driving
 * `falcon` (the Alexa client). We suppress millennium — it competes for our handset-mic slot, which also
 * disables native "Hey Alexa" — while leaving falcon alone (it never contends). So "hey alexa" is ours to
 * listen for, but **only when falcon is installed**, so we never hand the mic to an absent consumer (which
 * would never record, leaving us unable to detect a release).
 */
object WakeRegistry {

    /** Discover the wake set: the wake plugins' declared targets plus the built-in defaults they don't cover. */
    fun discover(context: Context): WakeSet {
        val pm = context.packageManager
        val nativeAlexaInstalled = isInstalled(pm, Falcon.PACKAGE)
        if (!nativeAlexaInstalled) DebugLog.log("registry: ${Falcon.PACKAGE} not installed — skipping 'hey alexa'")
        val set = WakeSet.of(queryHandlers(pm), nativeAlexaInstalled)
        DebugLog.log("registry: $set")
        return set
    }

    // ---- runtime discovery -------------------------------------------------------------------------

    private fun queryHandlers(pm: PackageManager): List<WakeTarget> = runCatching {
        val intent = Intent(WakeContract.ACTION_WAKE)

        // DEPRECATION: the ResolveInfoFlags overload is API 33+ and minSdk is 28. QueryPermissionsNeeded: the
        // manifest's <queries> declares ACTION_WAKE, and targetSdk 29 predates package-visibility filtering anyway.
        @Suppress("DEPRECATION", "QueryPermissionsNeeded")
        val resolved = pm.queryBroadcastReceivers(intent, PackageManager.GET_META_DATA)
        resolved
            .mapNotNull { it.activityInfo }
            .mapNotNull { info -> info.metaData?.let { meta -> pluginTarget(info, meta) } }
    }.getOrElse {
        DebugLog.log("registry query failed: ${it.message}")
        emptyList()
    }

    /** The wake target one plugin receiver declares in its [meta]-data, or null if the declaration is rejected. */
    private fun pluginTarget(info: ActivityInfo, meta: Bundle): WakeTarget? {
        val component = ComponentName(info.packageName, info.name)
        // One receiver declares one wake word (more words = more receivers). WakeDeclaration reads its
        // meta-data fields and reports any mistake (with the bad field) so a developer can fix it.
        val word = WakeDeclaration.build(
            read = { key -> meta.text(key) },
            onProblem = { reason ->
                DebugLog.log("registry: wake plugin ${component.flattenToShortString()}: $reason")
            },
        )
        return word?.let { WakeTarget(it, Consumer.Plugin(packageName = info.packageName, className = info.name)) }
    }

    /**
     * Read one meta-data field as text, whatever type Android inferred for `android:value` (a numeric value
     * like `0.55` is stored as a Float, so `getString` would return null — `get()?.toString()` keeps it, and
     * also lets a typo'd value reach [WakeDeclaration]'s "not a number" diagnostic instead of looking absent).
     */
    // DEPRECATION: the untyped Bundle.get() is the point (the value's type depends on how Android parsed it), and
    // the typed replacement getters can't read "whatever type it is".
    @Suppress("DEPRECATION")
    private fun Bundle.text(key: String): String? = get(key)?.toString()

    private fun isInstalled(pm: PackageManager, pkg: String): Boolean = runCatching {
        // DEPRECATION: the PackageInfoFlags overload is API 33+ and minSdk is 28.
        @Suppress("DEPRECATION")
        pm.getPackageInfo(pkg, 0)
        true
    }.getOrDefault(false)
}
