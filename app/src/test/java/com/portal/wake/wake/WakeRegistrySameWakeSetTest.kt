package com.portal.wake.wake

import com.portal.wake.audio.WakeMatcher
import com.portal.wake.audio.WakeWord
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests [WakeRegistry.sameWakeSet] — the filter that lets the service ignore a package change that doesn't
 * actually move the wake set. A reinstall of a wake plugin that keeps the same keywords (or any unrelated
 * app) must compare equal so no model reload / grammar swap happens; a genuine add/remove/change must not.
 */
class WakeRegistrySameWakeSetTest {

    private fun word(id: String, minConf: Double = 0.5) = WakeWord(id, keyword = id, lead = "hey", minConf = minConf)

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
        val a = listOf(word("jarvis", minConf = 0.5))
        val b = listOf(word("jarvis", minConf = 0.7))
        assertFalse(WakeRegistry.sameWakeSet(a, b))
    }

    @Test fun bothEmptyAreEqual() {
        assertTrue(WakeRegistry.sameWakeSet(emptyList(), emptyList()))
    }

    @Test fun emptyVsNonEmptyDiffers() {
        assertFalse(WakeRegistry.sameWakeSet(emptyList(), listOf(word("jarvis"))))
    }

    /**
     * Regression guard for the routing bug: the built-in jarvis default and the assistant's manifest spec
     * `jarvis;hey jarvis;jarvis;0.6` are an IDENTICAL [WakeWord] (STRICT_MIN_CONF == 0.60), so installing or
     * uninstalling the assistant does NOT change the wake-word set — only the routing (component null ↔ the
     * handler). sameWakeSet therefore returns true across that transition, which is exactly why
     * WakeService.applyWakeSetChange must refresh `targets` independently of the word comparison. If this
     * ever returns false, the word-vs-routing decoupling assumption has changed.
     */
    @Test fun builtinJarvisEqualsAssistantSpec_soWordsAreUnchangedAcrossInstall() {
        val builtin = listOf(WakeRegistry.BUILTIN_JARVIS)
        val fromPluginSpec = listOf(
            WakeSpec.build("hey jarvis", id = "jarvis", minConfidence = "0.6", defaultMinConf = WakeMatcher.BASELINE_CONF) {}!!,
        )
        assertTrue(WakeRegistry.sameWakeSet(builtin, fromPluginSpec))
    }
}
