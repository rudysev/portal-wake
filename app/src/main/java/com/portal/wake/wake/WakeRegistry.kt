package com.portal.wake.wake

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.portal.commons.DebugLog
import com.portal.wake.audio.WakeMatcher
import com.portal.wake.audio.WakeWord
import com.portal.wake.system.Falcon

/**
 * Builds the live set of [WakeTarget]s — **what to listen for and where to route it** — by *discovering*
 * handler apps at runtime, with a small built-in fallback so the app is useful before any plugin exists.
 *
 * Discovery (the extensible path): every app that responds to [WakeContract.ACTION_WAKE] and declares a
 * [WakeContract.META_KEYWORDS] meta-data string is a wake plugin. We read its specs and route matches to
 * its component. Adding "hey X" is therefore a manifest entry in *that* app — no change here.
 *
 * Fallback (defaults): when nothing is discovered for an id we fall back to [defaults] so the recognizer
 * has something to do out of the box. A discovered handler always overrides a default with the same id.
 *
 * Built-in defaults are "hey jarvis" and "hey alexa", both verified on device at confidence 1.00. The
 * Portal's Alexa stack is `com.millennium` (the "Hey Alexa" Vosk detector, on the handset mic) driving
 * `falcon` (the Alexa client). We suppress millennium — it competes for our handset-mic slot, which
 * also disables native "Hey Alexa" — while leaving falcon alone (it never contends). So we own both
 * words; a future "hey alexa" hand-off would invoke Alexa by broadcasting falcon's LISTEN intent.
 */
object WakeRegistry {

    /**
     * Discover all wake targets: runtime-declared handlers first, then defaults for any id not covered.
     * Returns at least the always-available defaults (currently "hey jarvis").
     */
    fun discover(context: Context): List<WakeTarget> {
        val pm = context.packageManager
        val discovered = queryHandlers(pm)
        val coveredIds = discovered.map { it.word.id }.toSet()
        val fallback = defaults(pm).filter { it.word.id !in coveredIds }
        val all = discovered + fallback
        DebugLog.log("registry: ${all.map { "${it.word.id}${if (it.component != null) "→${it.source}" else "(log)"}" }}")
        return all
    }

    /** Convenience: just the wake words (what the recognizer needs), in discovery order. */
    fun wakeWords(targets: List<WakeTarget>): List<WakeWord> = targets.map { it.word }

    /**
     * True when two wake sets are equivalent (order-insensitive). The service uses this to skip a rebuild
     * when a package change leaves the discovered wake set unchanged — i.e. the changed app isn't a wake
     * plugin (or its keywords didn't move). Pure (no Android), so it is unit-tested. [WakeWord] is a data
     * class, so set equality compares id/phrase/keyword/minConf.
     */
    fun sameWakeSet(a: List<WakeWord>, b: List<WakeWord>): Boolean = a.toSet() == b.toSet()

    // ---- runtime discovery -------------------------------------------------------------------------

    private fun queryHandlers(pm: PackageManager): List<WakeTarget> = runCatching {
        val intent = Intent(WakeContract.ACTION_WAKE)

        @Suppress("DEPRECATION", "QueryPermissionsNeeded")
        val resolved = pm.queryBroadcastReceivers(intent, PackageManager.GET_META_DATA)
        buildList {
            for (ri in resolved) {
                val info = ri.activityInfo ?: continue
                val spec = info.metaData?.getString(WakeContract.META_KEYWORDS) ?: continue
                val component = ComponentName(info.packageName, info.name)
                for (w in parseSpecs(spec)) add(WakeTarget(w, component, info.packageName))
            }
        }
    }.getOrElse {
        DebugLog.log("registry query failed: ${it.message}")
        emptyList()
    }

    /**
     * Parse a [WakeContract.META_KEYWORDS] value: specs separated by '|', each `id;phrase;keyword[;minConf]`.
     * Malformed specs are skipped (logged), so one bad plugin can't break discovery.
     */
    internal fun parseSpecs(value: String): List<WakeWord> = WakeKeywordParser.parse(value, WakeMatcher.BASELINE_CONF) { raw ->
        DebugLog.log("registry: bad spec \"$raw\"")
    }.map { WakeWord(it.id, it.phrase, it.keyword, it.minConf) }

    // ---- built-in defaults -------------------------------------------------------------------------

    /**
     * The built-in "jarvis" wake word (fallback when no plugin declares it). Exposed so a unit test can lock
     * in its strict floor without an Android `PackageManager`; the installed assistant overrides it via its
     * manifest spec.
     */
    internal val BUILTIN_JARVIS = WakeWord("jarvis", "hey jarvis", "jarvis", WakeMatcher.STRICT_MIN_CONF)

    /**
     * Built-in wake words used when no plugin covers them. "jarvis" routes to a registered handler (the
     * assistant) when installed, else logs. "alexa" is a built-in convenience route to the Portal's
     * native Alexa client (falcon) — **only advertised when falcon is installed**, so we never hand the
     * mic to an absent consumer (which would never record, leaving us unable to detect a release).
     *
     * Both use [WakeMatcher.STRICT_MIN_CONF] (above [WakeMatcher.BASELINE_CONF]) so the lenient clean-phrase
     * bypass is OFF and a real confidence floor is required — "jarvis" decodes false-positive-prone
     * soundalikes more readily than the old default did. Precision still comes mainly from the required
     * "hey". (The installed assistant overrides this default via its own manifest spec, which carries the
     * matching strict floor.)
     */
    private fun defaults(pm: PackageManager): List<WakeTarget> = buildList {
        add(WakeTarget(BUILTIN_JARVIS, component = null, source = null))
        if (isInstalled(pm, Falcon.PACKAGE)) {
            add(WakeTarget(WakeWord("alexa", "hey alexa", "alexa", WakeMatcher.STRICT_MIN_CONF), component = null, source = null))
        } else {
            DebugLog.log("registry: ${Falcon.PACKAGE} not installed — skipping 'hey alexa'")
        }
    }

    private fun isInstalled(pm: PackageManager, pkg: String): Boolean = runCatching {
        @Suppress("DEPRECATION")
        pm.getPackageInfo(
            pkg,
            0,
        )
        true
    }.getOrDefault(false)
}
