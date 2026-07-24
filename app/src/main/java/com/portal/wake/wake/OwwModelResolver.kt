package com.portal.wake.wake

import android.content.Context
import com.portal.commons.DebugLog
import com.portal.commons.audio.OpenWakeWordDetector

/**
 * Splits the discovered wake set into the two detection paths, by **whether an openWakeWord classifier
 * exists for the phrase**.
 *
 * - A phrase **with** a model (portal-wake bundles "hey jarvis" and "hey alexa"; a plugin ships its own via
 *   [WakeContract.META_MODEL]) goes to the **two-stage cascade** — openWakeWord proposes, a
 *   phrase-constrained Vosk decode disposes.
 * - A phrase **without** one stays **Vosk-primary**, detected by grammar decode exactly as every phrase was
 *   before openWakeWord existed, honouring its [WakeContract.META_MIN_CONFIDENCE]. openWakeWord cannot
 *   detect a phrase it has no classifier for, so this is not a fallback so much as the only option — and it
 *   is why the plugin API keeps working unchanged for a plugin that ships no model.
 *
 * The split is what makes the two detectors a **partition rather than a race**: `WakeRouting` gives each id
 * exactly one detector that may hand off (see [com.portal.commons.audio.WakeRouting]). Running both over
 * the *same* phrase would be an OR, which unions their false accepts.
 */
object OwwModelResolver {

    /**
     * Stage-1 classifier configs for every target that has a model. A target with none is logged once and
     * omitted — its id then falls outside `owwOwnedIds`, so Vosk routes it.
     */
    fun resolve(context: Context, targets: List<WakeTarget>): List<OpenWakeWordDetector.PhraseClassifierConfig> = buildList {
        for (target in targets) {
            val word = target.word
            val bytes = pluginModel(context, target) ?: OpenWakeWordDetector.loadBuiltinModel(context, word)
            if (bytes == null) {
                DebugLog.log(
                    "registry: no openWakeWord model for '${word.id}' (${word.phrase}) — Vosk-primary " +
                        "(declare '${WakeContract.META_MODEL}' to use the two-stage path)",
                )
                continue
            }
            add(
                OpenWakeWordDetector.PhraseClassifierConfig(
                    wakeId = word.id,
                    modelBytes = bytes,
                    scoreThreshold = word.scoreThreshold.toFloat().coerceIn(0f, 1f),
                ),
            )
        }
    }

    /** The ids the cascade covers — everything else must be routed by Vosk. */
    fun ownedIds(configs: List<OpenWakeWordDetector.PhraseClassifierConfig>): Set<String> = configs.mapTo(HashSet()) { it.wakeId }

    /**
     * True when some discovered phrase has no model, i.e. the streaming Vosk detector must run alongside the
     * cascade to cover it. Worth asking rather than always configuring both: each Vosk consumer loads its
     * own `Model`, so the common case (built-ins only, both of which have models) stays at one.
     */
    fun needsVoskPrimary(targets: List<WakeTarget>, owned: Set<String>): Boolean = targets.any { it.word.id !in owned }

    /**
     * The part of a wake set that determines the **detector composition**: for each phrase, whether it is
     * model-backed (and by which model) and its stage-1 threshold.
     *
     * The detector list is fixed when the engine is built, so a change here cannot be applied by an in-place
     * grammar swap — `WakeService` rebuilds the engine when the shape changes and swaps grammar when only
     * the words did. Compares the *declared* model asset (or the bundled asset name), so it is a cheap
     * comparison of manifests rather than a re-read of every plugin's assets. Order-insensitive.
     */
    fun detectorShape(targets: List<WakeTarget>): List<String> = targets
        .map { t ->
            val model = t.modelAsset?.takeIf { it.isNotBlank() }?.let { "${t.source}:$it" }
                ?: OpenWakeWordDetector.builtinAssetFor(t.word)
            "${t.word.id}|$model|${t.word.scoreThreshold}"
        }
        .sorted()

    /**
     * A plugin-supplied `.onnx`, read from the **declaring app's** assets. Null when the target declares
     * none, or when reading fails — a broken path must not take down discovery, so it is logged and the
     * phrase falls through to the built-in lookup and then to Vosk-primary.
     */
    private fun pluginModel(context: Context, target: WakeTarget): ByteArray? {
        val asset = target.modelAsset?.takeIf { it.isNotBlank() } ?: return null
        val pkg = target.source ?: return null
        return runCatching {
            context.createPackageContext(pkg, 0).assets.open(asset).use { it.readBytes() }
        }.getOrElse {
            DebugLog.log("registry: failed to load openWakeWord model '$asset' from $pkg: ${it.message}")
            null
        }
    }
}
