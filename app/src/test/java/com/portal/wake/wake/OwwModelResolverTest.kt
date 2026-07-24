package com.portal.wake.wake

import android.content.ComponentName
import com.portal.commons.audio.OpenWakeWordDetector
import com.portal.commons.audio.TwoStageTuning
import com.portal.commons.audio.WakeWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure parts of [OwwModelResolver]: which phrases the two-stage cascade owns, when the streaming Vosk
 * detector is still needed alongside it, and when a wake-set change forces an engine rebuild rather than an
 * in-place grammar swap. Loading the actual `.onnx` bytes needs a real `Context` and is device-tested.
 */
class OwwModelResolverTest {

    private fun word(phrase: String, id: String? = null, score: Double = TwoStageTuning.DEFAULT_STAGE1_THRESHOLD) = WakeWord.fromPhrase(phrase, id = id, scoreThreshold = score)!!

    private fun target(w: WakeWord, model: String? = null, source: String? = null) = WakeTarget(w, source?.let { ComponentName(it, "$it.Receiver") }, source, model)

    private fun config(id: String, threshold: Float = 0.30f) = OpenWakeWordDetector.PhraseClassifierConfig(id, ByteArray(1), threshold)

    // ---- ownedIds --------------------------------------------------------------------------------------

    @Test fun ownedIdsAreTheOnesWithAClassifier() {
        assertEquals(setOf("jarvis", "alexa"), OwwModelResolver.ownedIds(listOf(config("jarvis"), config("alexa"))))
    }

    @Test fun ownedIdsIsEmptyWhenNothingHasAModel() {
        assertEquals(emptySet<String>(), OwwModelResolver.ownedIds(emptyList()))
    }

    // ---- needsVoskPrimary ------------------------------------------------------------------------------

    @Test fun voskIsNotNeededWhenEveryPhraseHasAModel() {
        // The common case — the two built-ins both ship bundled models, so only the cascade's Vosk model
        // is loaded, not a second one.
        val targets = listOf(target(word("hey jarvis")), target(word("hey alexa", id = "alexa")))
        assertFalse(OwwModelResolver.needsVoskPrimary(targets, setOf("jarvis", "alexa")))
    }

    @Test fun voskIsNeededForAPhraseWithNoModel() {
        // openWakeWord cannot detect a phrase it has no classifier for, so that phrase must stay on the
        // path it used before openWakeWord existed rather than becoming undetectable.
        val targets = listOf(target(word("hey jarvis")), target(word("hey bob"), source = "com.example"))
        assertTrue(OwwModelResolver.needsVoskPrimary(targets, setOf("jarvis")))
    }

    @Test fun voskIsNeededWhenNothingHasAModel() {
        assertTrue(OwwModelResolver.needsVoskPrimary(listOf(target(word("hey bob"))), emptySet()))
    }

    // ---- detectorShape ---------------------------------------------------------------------------------

    @Test fun shapeIsUnchangedByOrder() {
        val a = listOf(target(word("hey jarvis")), target(word("hey alexa", id = "alexa")))
        assertEquals(OwwModelResolver.detectorShape(a), OwwModelResolver.detectorShape(a.reversed()))
    }

    @Test fun shapeIsUnchangedByRoutingOnly() {
        // Installing the handler for an already-declared word changes the component, not the detectors —
        // this is the case the in-place grammar swap exists for.
        val builtin = listOf(target(word("hey jarvis")))
        val installed = listOf(target(word("hey jarvis"), source = "com.portal.assistant"))
        assertEquals(OwwModelResolver.detectorShape(builtin), OwwModelResolver.detectorShape(installed))
    }

    @Test fun shapeChangesWhenAPhraseGainsAModel() {
        val without = listOf(target(word("hey bob"), source = "com.example"))
        val with = listOf(target(word("hey bob"), model = "oww/hey_bob.onnx", source = "com.example"))
        assertTrue(OwwModelResolver.detectorShape(without) != OwwModelResolver.detectorShape(with))
    }

    @Test fun shapeChangesWhenAPluginSwapsItsModelKeepingThePhrase() {
        // A plugin update can leave the wake set identical, so this is checked even when the words match —
        // otherwise the new model would never be loaded.
        val v1 = listOf(target(word("hey bob"), model = "oww/bob_v1.onnx", source = "com.example"))
        val v2 = listOf(target(word("hey bob"), model = "oww/bob_v2.onnx", source = "com.example"))
        assertTrue(OwwModelResolver.detectorShape(v1) != OwwModelResolver.detectorShape(v2))
    }

    @Test fun shapeChangesWhenTheStageOneThresholdChanges() {
        val loose = listOf(target(word("hey bob", score = 0.30), model = "m.onnx", source = "com.example"))
        val tight = listOf(target(word("hey bob", score = 0.70), model = "m.onnx", source = "com.example"))
        assertTrue(OwwModelResolver.detectorShape(loose) != OwwModelResolver.detectorShape(tight))
    }

    @Test fun sameAssetPathFromDifferentPluginsIsADifferentShape() {
        val a = listOf(target(word("hey bob"), model = "oww/m.onnx", source = "com.a"))
        val b = listOf(target(word("hey bob"), model = "oww/m.onnx", source = "com.b"))
        assertTrue(OwwModelResolver.detectorShape(a) != OwwModelResolver.detectorShape(b))
    }

    @Test fun aBuiltinBackedPhraseHasAModelInItsShape() {
        // "hey jarvis" resolves to a bundled classifier with no declaration, so its shape must not read the
        // same as a phrase with no model at all.
        val builtin = OwwModelResolver.detectorShape(listOf(target(word("hey jarvis"))))
        val none = OwwModelResolver.detectorShape(listOf(target(word("hey bob"))))
        assertTrue(builtin.single().contains(OpenWakeWordDetector.JARVIS_ASSET))
        assertTrue(none.single().contains("null"))
    }
}
