package com.portal.wake.wake

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import com.portal.commons.DebugLog
import com.portal.commons.audio.WakeMatcher
import com.portal.commons.audio.WakeWord
import com.portal.wake.system.Falcon

/**
 * Builds the live set of [WakeTarget]s — **what to listen for and where to route it** — by *discovering*
 * handler apps at runtime, with a small built-in fallback so the app is useful before any plugin exists.
 *
 * Discovery (the extensible path): every app that responds to [WakeContract.ACTION_WAKE] and declares a
 * [WakeContract.META_PHRASE] (plus optional id/min-confidence) meta-data is a wake plugin. We build its
 * wake word ([WakeSpec]) and route matches to its component. Adding "hey X" is therefore a manifest entry
 * in *that* app — no change here.
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
     * plugin (or its declared wake word didn't change). Pure (no Android), so it is unit-tested. [WakeWord] is a data
     * class, so set equality compares its constructor fields id/keyword/lead/minConf (`phrase` is a derived
     * getter, excluded from equals — but it's a pure function of keyword+lead, so nothing is lost).
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
                val meta = info.metaData ?: continue
                val component = ComponentName(info.packageName, info.name)
                // One receiver declares one wake word (more words = more receivers). Build it from the named
                // meta-data fields; WakeSpec reports any mistake (with the bad field) so a developer can fix it.
                val phrase = meta.text(WakeContract.META_PHRASE)
                val word = WakeSpec.build(
                    phrase = phrase,
                    id = meta.text(WakeContract.META_ID),
                    minConfidence = meta.text(WakeContract.META_MIN_CONFIDENCE),
                    defaultMinConf = WakeMatcher.BASELINE_CONF,
                    onProblem = { reason -> DebugLog.log("registry: wake plugin ${component.flattenToShortString()}: $reason") },
                ) ?: continue
                warnIfMisdeclared(component, phrase, word)
                add(WakeTarget(word, component, info.packageName))
            }
        }
    }.getOrElse {
        DebugLog.log("registry query failed: ${it.message}")
        emptyList()
    }

    /**
     * Read one meta-data field as text, whatever type Android inferred for `android:value` (a numeric value
     * like `0.55` is stored as a Float, so `getString` would return null — `get()?.toString()` keeps it, and
     * also lets a typo'd value reach [WakeSpec]'s "not a number" diagnostic instead of looking absent).
     */
    @Suppress("DEPRECATION")
    private fun Bundle.text(key: String): String? = get(key)?.toString()

    /**
     * Surface the two declaration mistakes that *build* a word but make it behave surprisingly (so they
     * aren't silent like an outright rejection): a phrase longer than two words (only the last two are kept,
     * so the rest never reach the grammar), and a strict word with no lead (it fires on the bare keyword).
     */
    private fun warnIfMisdeclared(component: ComponentName, declaredPhrase: String?, w: WakeWord) {
        val wordCount = WakeWord.tokenize(declaredPhrase.orEmpty()).size
        if (wordCount > 2) {
            DebugLog.log(
                "registry: ${component.flattenToShortString()} phrase \"$declaredPhrase\" has $wordCount words — " +
                    "only the last two are used (lead \"${w.lead}\" + keyword \"${w.keyword}\"); declare a two-word phrase",
            )
        }
        if (w.lead == null && w.minConf > WakeMatcher.BASELINE_CONF) {
            DebugLog.log(
                "registry: ${component.flattenToShortString()} wake word '${w.keyword}' is strict but declares " +
                    "no lead — it will fire on the bare keyword; declare a lead (e.g. \"hey ${w.keyword}\")",
            )
        }
    }

    // ---- built-in defaults -------------------------------------------------------------------------

    /**
     * The built-in "jarvis" wake word (fallback when no plugin declares it). Exposed so a unit test can lock
     * in its strict floor without an Android `PackageManager`; the installed assistant overrides it via its
     * manifest spec.
     */
    internal val BUILTIN_JARVIS = WakeWord.fromPhrase("hey jarvis", id = "jarvis", minConf = WakeMatcher.STRICT_MIN_CONF)!!

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
            add(WakeTarget(WakeWord.fromPhrase("hey alexa", id = "alexa", minConf = WakeMatcher.STRICT_MIN_CONF)!!, component = null, source = null))
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
