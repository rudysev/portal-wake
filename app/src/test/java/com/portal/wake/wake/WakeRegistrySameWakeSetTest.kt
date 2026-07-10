package com.portal.wake.wake

import com.portal.commons.audio.WakeWord
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests [WakeRegistry.sameWakeSet] — the filter that lets the service ignore a package change that doesn't
 * actually move the wake set. A reinstall of a wake plugin that keeps the same keywords (or any unrelated
 * app) must compare equal so no detector reload happens; a genuine add/remove/change must not.
 */
class WakeRegistrySameWakeSetTest {

    private fun word(id: String, scoreThreshold: Double = 0.5) =
        WakeWord(
            id,
            keyword = id,
            lead = "hey",
            minConf = WakeWord.DEFAULT_MIN_CONF,
            scoreThreshold = scoreThreshold,
        )

    @Test fun identicalSetsAreEqual() {
        val a = listOf(word("jarvis"), word("alexa"))
        val b = listOf(word("jarvis"), word("alexa"))
        assertTrue(WakeRegistry.sameWakeSet(a, b))
    }

    @Test fun orderDoesNotMatter() {
        val a = listOf(word("jarvis"), word("alexa"))
        val b = listOf(word("alexa"), word("jarvis"))
        assertTrue(WakeRegistry.sameWakeSet(a, b))
    }

    @Test fun addedWordDiffers() {
        val a = listOf(word("jarvis"))
        val b = listOf(word("jarvis"), word("alexa"))
        assertFalse(WakeRegistry.sameWakeSet(a, b))
    }

    @Test fun removedWordDiffers() {
        val a = listOf(word("jarvis"), word("alexa"))
        val b = listOf(word("jarvis"))
        assertFalse(WakeRegistry.sameWakeSet(a, b))
    }

    @Test fun changedFieldDiffers() {
        // Same id but a different keyword/phrase/minConf is a real change (data-class equality).
        val a = listOf(word("jarvis", scoreThreshold = 0.5))
        val b = listOf(word("jarvis", scoreThreshold = 0.7))
        assertFalse(WakeRegistry.sameWakeSet(a, b))
    }

    @Test fun bothEmptyAreEqual() {
        assertTrue(WakeRegistry.sameWakeSet(emptyList(), emptyList()))
    }

    @Test fun emptyVsNonEmptyDiffers() {
        assertFalse(WakeRegistry.sameWakeSet(emptyList(), listOf(word("jarvis"))))
    }

    /**
     * Regression guard for the routing bug: the built-in jarvis default and the assistant's manifest
     * "hey jarvis" / id jarvis / min_confidence 0.5 are an IDENTICAL [WakeWord], so installing or
     * uninstalling the assistant does NOT change the wake-word set — only the routing (component null ↔ the
     * handler). sameWakeSet therefore returns true across that transition, which is exactly why
     * WakeService.applyWakeSetChange must refresh `targets` independently of the word comparison. If this
     * ever returns false, the word-vs-routing decoupling assumption has changed.
     */
    @Test fun builtinJarvisEqualsAssistantSpec_soWordsAreUnchangedAcrossInstall() {
        val builtin = listOf(WakeRegistry.BUILTIN_JARVIS)
        val fromPluginSpec = listOf(
            WakeSpec.build("hey jarvis", id = "jarvis", minConfidence = "0.5", defaultMinConf = WakeWord.DEFAULT_SCORE_THRESHOLD) {}!!,
        )
        assertTrue(WakeRegistry.sameWakeSet(builtin, fromPluginSpec))
    }

    private fun target(
        id: String,
        modelAsset: String? = null,
        scoreThreshold: Double = 0.5,
    ) = WakeTarget(
        word = word(id, scoreThreshold),
        component = null,
        source = if (modelAsset != null) "com.example.plugin" else null,
        modelAsset = modelAsset,
    )

    @Test fun sameDetectableSet_ignoresRoutingOnly() {
        val builtin = listOf(target("jarvis"))
        val withHandler = listOf(
            WakeTarget(
                word = word("jarvis"),
                component = null, // ComponentName needs Android; null still differs by source
                source = "com.portal.assistant",
                modelAsset = null,
            ),
        )
        assertTrue(WakeRegistry.sameDetectableSet(builtin, withHandler))
    }

    @Test fun sameDetectableSet_modelAssetChangeRequiresReload() {
        val before = listOf(target("custom", modelAsset = "oww/old.onnx"))
        val after = listOf(target("custom", modelAsset = "oww/new.onnx"))
        assertTrue(WakeRegistry.sameWakeSet(WakeRegistry.wakeWords(before), WakeRegistry.wakeWords(after)))
        assertFalse(
            "plugin ONNX path change must not be treated as detectable-unchanged",
            WakeRegistry.sameDetectableSet(before, after),
        )
    }

    @Test fun sameDetectableSet_addingModelAssetRequiresReload() {
        val before = listOf(target("jarvis", modelAsset = null))
        val after = listOf(target("jarvis", modelAsset = "oww/hey_jarvis_v0.1.onnx"))
        assertFalse(WakeRegistry.sameDetectableSet(before, after))
    }

    @Test fun sameDetectableSet_identicalModelsMatch() {
        val a = listOf(target("jarvis"), target("custom", modelAsset = "models/x.onnx"))
        val b = listOf(target("custom", modelAsset = "models/x.onnx"), target("jarvis"))
        assertTrue(WakeRegistry.sameDetectableSet(a, b))
    }
}
