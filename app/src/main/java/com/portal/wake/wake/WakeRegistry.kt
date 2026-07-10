package com.portal.wake.wake

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import com.portal.commons.DebugLog
import com.portal.commons.audio.OpenWakeWordDetector
import com.portal.commons.audio.WakeWord
import com.portal.wake.system.Falcon

/**
 * Builds the live set of [WakeTarget]s — **what to listen for and where to route it** — by *discovering*
 * handler apps at runtime, with a small built-in fallback so the app is useful before any plugin exists.
 *
 * Discovery (the extensible path): every app that responds to [WakeContract.ACTION_WAKE] and declares a
 * [WakeContract.META_PHRASE] (plus optional id/threshold/model) meta-data is a wake plugin. We build its
 * wake word ([WakeSpec]) and route matches to its component. Adding "hey X" is therefore a manifest entry
 * in *that* app — no change here.
 *
 * Fallback (defaults): when nothing is discovered for an id we fall back to [defaults] so the detector
 * has something to do out of the box. A discovered handler always overrides a default with the same id.
 *
 * Detection uses **openWakeWord** neural models (bundled for jarvis/alexa; plugin-supplied for custom words).
 * A discovered word is listenable only when an ONNX classifier exists for it — see [OwwHeadResolver].
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

    /** Convenience: just the wake words (what the detector needs), in discovery order. */
    fun wakeWords(targets: List<WakeTarget>): List<WakeWord> = targets.map { it.word }

    /**
     * True when two wake sets are equivalent (order-insensitive). The service uses this to skip a rebuild
     * when a package change leaves the discovered wake set unchanged — i.e. the changed app isn't a wake
     * plugin (or its declared wake word didn't change). Pure (no Android), so it is unit-tested.
     */
    fun sameWakeSet(a: List<WakeWord>, b: List<WakeWord>): Boolean = a.toSet() == b.toSet()

    /**
     * True when two target lists are equivalent for **detection** (order-insensitive): same [WakeWord]s
     * and the same per-id [WakeTarget.modelAsset]. Routing ([WakeTarget.component] / [WakeTarget.source])
     * is ignored — the service refreshes `targets` for routing even when this returns true.
     *
     * A plugin APK update that only changes the ONNX path (or adds/removes [WakeContract.META_MODEL])
     * must return false so the engine reloads classifiers.
     */
    fun sameDetectableSet(a: List<WakeTarget>, b: List<WakeTarget>): Boolean {
        if (!sameWakeSet(wakeWords(a), wakeWords(b))) return false
        val modelsA = a.associate { it.word.id to it.modelAsset }
        val modelsB = b.associate { it.word.id to it.modelAsset }
        return modelsA == modelsB
    }

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
                val phrase = meta.text(WakeContract.META_PHRASE)
                val word = WakeSpec.build(
                    phrase = phrase,
                    id = meta.text(WakeContract.META_ID),
                    minConfidence = meta.text(WakeContract.META_MIN_CONFIDENCE),
                    defaultMinConf = WakeWord.DEFAULT_SCORE_THRESHOLD,
                    onProblem = { reason -> DebugLog.log("registry: wake plugin ${component.flattenToShortString()}: $reason") },
                ) ?: continue
                warnIfMisdeclared(component, phrase, word, meta.text(WakeContract.META_MODEL))
                add(
                    WakeTarget(
                        word = word,
                        component = component,
                        source = info.packageName,
                        modelAsset = meta.text(WakeContract.META_MODEL),
                    ),
                )
            }
        }
    }.getOrElse {
        DebugLog.log("registry query failed: ${it.message}")
        emptyList()
    }

    @Suppress("DEPRECATION")
    private fun Bundle.text(key: String): String? = get(key)?.toString()

    private fun warnIfMisdeclared(
        component: ComponentName,
        declaredPhrase: String?,
        w: WakeWord,
        modelAsset: String?,
    ) {
        val wordCount = WakeWord.tokenize(declaredPhrase.orEmpty()).size
        if (wordCount > 2) {
            DebugLog.log(
                "registry: ${component.flattenToShortString()} phrase \"$declaredPhrase\" has $wordCount words — " +
                    "only the last two are kept (lead \"${w.lead}\" + keyword \"${w.keyword}\"); declare a two-word phrase",
            )
        }
        if (modelAsset.isNullOrBlank() && OpenWakeWordDetector.builtinAssetFor(w) == null) {
            DebugLog.log(
                "registry: ${component.flattenToShortString()} wake '${w.id}' has no bundled model and no " +
                    "'${WakeContract.META_MODEL}' — it won't be detectable until a plugin ONNX is shipped",
            )
        }
    }

    // ---- built-in defaults -------------------------------------------------------------------------

    internal val BUILTIN_JARVIS =
        WakeWord.fromPhrase("hey jarvis", id = "jarvis", scoreThreshold = WakeWord.DEFAULT_SCORE_THRESHOLD)!!

    private fun defaults(pm: PackageManager): List<WakeTarget> = buildList {
        add(WakeTarget(BUILTIN_JARVIS, component = null, source = null))
        if (isInstalled(pm, Falcon.PACKAGE)) {
            add(
                WakeTarget(
                    WakeWord.fromPhrase("hey alexa", id = "alexa", scoreThreshold = WakeWord.DEFAULT_SCORE_THRESHOLD)!!,
                    component = null,
                    source = null,
                ),
            )
        } else {
            DebugLog.log("registry: ${Falcon.PACKAGE} not installed — skipping 'hey alexa'")
        }
    }

    private fun isInstalled(pm: PackageManager, pkg: String): Boolean = runCatching {
        @Suppress("DEPRECATION")
        pm.getPackageInfo(pkg, 0)
        true
    }.getOrDefault(false)
}
