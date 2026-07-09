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
 */
object WakeRegistry {

    fun discover(context: Context): List<WakeTarget> {
        val pm = context.packageManager
        val discovered = queryHandlers(pm)
        val coveredIds = discovered.map { it.word.id }.toSet()
        val fallback = defaults(pm).filter { it.word.id !in coveredIds }
        val all = discovered + fallback
        DebugLog.log("registry: ${all.map { "${it.word.id}${if (it.component != null) "→${it.source}" else "(log)"}" }}")
        return all
    }

    fun wakeWords(targets: List<WakeTarget>): List<WakeWord> = targets.map { it.word }

    fun sameWakeSet(a: List<WakeWord>, b: List<WakeWord>): Boolean = a.toSet() == b.toSet()

    /**
     * Fingerprint of which ONNX phrase classifiers the detector should load — wake id, model asset path,
     * plugin package revision, and score threshold. Used to rebuild the detector when a plugin APK is
     * updated even though the declared phrase/threshold are unchanged.
     */
    data class DetectionBinding(
        val wakeId: String,
        val modelAsset: String?,
        val sourcePackage: String?,
        val pluginRevision: Long,
        val scoreThreshold: Double,
    )

    fun detectionBindings(context: Context, targets: List<WakeTarget>): Set<DetectionBinding> =
        targets.map { target ->
            val revision = target.source?.let { pluginRevision(context, it) } ?: 0L
            DetectionBinding(
                wakeId = target.word.id,
                modelAsset = target.modelAsset,
                sourcePackage = target.source,
                pluginRevision = revision,
                scoreThreshold = target.word.scoreThreshold,
            )
        }.toSet()

    fun sameDetectionConfig(context: Context, a: List<WakeTarget>, b: List<WakeTarget>): Boolean =
        detectionBindings(context, a) == detectionBindings(context, b)

    private fun pluginRevision(context: Context, packageName: String): Long = runCatching {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        pm.getPackageInfo(packageName, 0).lastUpdateTime
    }.getOrDefault(0L)

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
                    defaultScoreThreshold = WakeWord.DEFAULT_SCORE_THRESHOLD,
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
