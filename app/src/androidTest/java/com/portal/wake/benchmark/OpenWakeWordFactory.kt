package com.portal.wake.benchmark

import androidx.test.platform.app.InstrumentationRegistry
import com.portal.commons.audio.OpenWakeWordDetector
import com.portal.commons.audio.WakeDetector
import com.portal.commons.audio.WakeWord

/**
 * openWakeWord factory for the benchmark harness — jarvis classifier only, at [scoreThreshold] (use 0f to
 * record peak scores and sweep the decision threshold offline; use [WakeWord.DEFAULT_SCORE_THRESHOLD] to
 * score at the shipped operating point).
 */
fun openWakeWordFactoryOrNull(scoreThreshold: Float = WakeWord.DEFAULT_SCORE_THRESHOLD.toFloat()): Pair<String, WakeDetector.Factory>? {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    if (!OpenWakeWordDetector.assetsPresent(context)) return null
    val word = WakeWord.fromPhrase("hey jarvis", id = "jarvis", scoreThreshold = scoreThreshold.toDouble())!!
    val bytes = OpenWakeWordDetector.loadBuiltinModel(context, word) ?: return null
    val config = OpenWakeWordDetector.PhraseClassifierConfig(word.id, bytes, scoreThreshold)
    return OpenWakeWordDetector.NAME to OpenWakeWordDetector.factory(listOf(config))
}
