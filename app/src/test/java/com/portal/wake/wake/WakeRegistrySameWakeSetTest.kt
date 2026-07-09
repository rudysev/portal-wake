package com.portal.wake.wake

import com.portal.commons.audio.WakeWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeRegistrySameWakeSetTest {

    private fun word(id: String, scoreThreshold: Double = 0.5) =
        WakeWord(id, keyword = id, lead = "hey", scoreThreshold = scoreThreshold)

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

    @Test fun changedScoreThresholdDiffers() {
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

    @Test fun builtinJarvisEqualsAssistantSpec_soWordsAreUnchangedAcrossInstall() {
        val builtin = listOf(WakeRegistry.BUILTIN_JARVIS)
        val fromPluginSpec = listOf(
            WakeSpec.build(
                "hey jarvis",
                id = "jarvis",
                minConfidence = "0.5",
                defaultScoreThreshold = WakeWord.DEFAULT_SCORE_THRESHOLD,
            ) {}!!,
        )
        assertTrue(WakeRegistry.sameWakeSet(builtin, fromPluginSpec))
    }
}
