package com.portal.wake.wake

import android.content.Context
import com.portal.commons.DebugLog
import com.portal.commons.audio.OpenWakeWordDetector

/**
 * Resolves [OpenWakeWordDetector.PhraseClassifierConfig]s for the discovered wake set: bundled models for
 * built-in words (jarvis, alexa) and ONNX assets shipped inside plugin APKs ([WakeContract.META_MODEL]).
 */
object OwwHeadResolver {

    fun resolve(context: Context, targets: List<WakeTarget>): List<OpenWakeWordDetector.PhraseClassifierConfig> =
        buildList {
            for (target in targets) {
                val word = target.word
                val bytes = when {
                    !target.modelAsset.isNullOrBlank() && target.source != null ->
                        loadPluginModel(context, target.source, target.modelAsset)
                    else ->
                        OpenWakeWordDetector.loadBuiltinModel(context, word)
                }
                if (bytes == null) {
                    DebugLog.log("registry: no oww model for '${word.id}' (${word.phrase}) — not detectable")
                    continue
                }
                val threshold = word.scoreThreshold.toFloat().coerceIn(0f, 1f)
                add(OpenWakeWordDetector.PhraseClassifierConfig(word.id, bytes, threshold))
            }
        }

    private fun loadPluginModel(context: Context, packageName: String, assetPath: String): ByteArray? =
        runCatching {
            val pkgContext = context.createPackageContext(packageName, 0)
            pkgContext.assets.open(assetPath).use { it.readBytes() }
        }.getOrElse {
            DebugLog.log("registry: failed to load oww model '$assetPath' from $packageName: ${it.message}")
            null
        }
}
