package com.portal.wake.wake

import com.portal.commons.audio.WakeWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeRegistryDetectionConfigTest {

    private fun target(
        id: String,
        modelAsset: String? = null,
        source: String? = null,
        scoreThreshold: Double = 0.5,
        revision: Long = 0L,
    ) = WakeTarget(
        word = WakeWord(id, keyword = id, lead = "hey", scoreThreshold = scoreThreshold),
        component = null,
        source = source,
        modelAsset = modelAsset,
    ) to revision

    private fun bindings(
        vararg entries: Pair<WakeTarget, Long>,
    ): Set<WakeRegistry.DetectionBinding> = entries.map { (t, rev) ->
        WakeRegistry.DetectionBinding(t.word.id, t.modelAsset, t.source, rev, t.word.scoreThreshold)
    }.toSet()

    @Test fun identicalBindingsAreEqual() {
        val a = bindings(target("jarvis"), target("alexa", modelAsset = null, source = "com.plugin", revision = 100L))
        val b = bindings(target("jarvis"), target("alexa", modelAsset = null, source = "com.plugin", revision = 100L))
        assertEquals(a, b)
    }

    @Test fun pluginRevisionChangeDiffers() {
        val a = bindings(target("jarvis", source = "com.plugin", revision = 100L))
        val b = bindings(target("jarvis", source = "com.plugin", revision = 200L))
        assertFalse(a == b)
    }

    @Test fun modelAssetPathChangeDiffers() {
        val a = bindings(target("custom", modelAsset = "oww/a.onnx", source = "com.plugin"))
        val b = bindings(target("custom", modelAsset = "oww/b.onnx", source = "com.plugin"))
        assertFalse(a == b)
    }

    @Test fun scoreThresholdChangeDiffers() {
        val a = bindings(target("jarvis", scoreThreshold = 0.5))
        val b = bindings(target("jarvis", scoreThreshold = 0.7))
        assertFalse(a == b)
    }

    @Test fun routingOnlyChangeWithSameBindingsIsEqual() {
        // Assistant install changes component but not detection bindings for jarvis builtin.
        val builtin = target("jarvis")
        val a = bindings(builtin)
        val b = bindings(builtin)
        assertTrue(a == b)
    }
}
