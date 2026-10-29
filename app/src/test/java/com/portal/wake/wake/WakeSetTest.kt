package com.portal.wake.wake

import com.portal.commons.audio.WakeMatcher
import com.portal.commons.audio.WakeWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** Tests [WakeSet] through its factory, wake words, lookup and refresh outcome. */
class WakeSetTest {

    /** A wake plugin target as the registry discovers it, from the plugin's declared meta-data. */
    private fun plugin(phrase: String, id: String, minConfidence: String, pkg: String) = WakeTarget(
        word = checkNotNull(
            WakeDeclaration.build(
                phrase,
                id = id,
                minConfidence = minConfidence,
                defaultMinConf = WakeMatcher.BASELINE_CONF,
            ) {
            },
        ),
        consumer = Consumer.Plugin(packageName = pkg, className = "$pkg.WakeReceiver"),
    )

    // The assistant's manifest declares exactly the built-in jarvis word (STRICT_MIN_CONF == 0.60).
    private val assistant = plugin("hey jarvis", id = "jarvis", minConfidence = "0.6", pkg = "com.portal.assistant")

    @Test fun noPluginsAndNoNativeAlexaGivesJustStrictJarvisWithNoConsumer() {
        val set = WakeSet.of(plugins = emptyList(), nativeAlexaInstalled = false)

        val word = set.words.single()
        assertEquals("jarvis", word.id)
        assertEquals("hey", word.lead)
        assertEquals("hey jarvis", word.phrase)
        assertEquals(WakeMatcher.STRICT_MIN_CONF, word.minConf, 1e-9)
        assertNull(set.target("jarvis")?.consumer)
    }

    @Test fun alexaIsListenedForOnlyWhenTheNativeAlexaClientIsInstalled() {
        val withAlexa = WakeSet.of(plugins = emptyList(), nativeAlexaInstalled = true)
        val withoutAlexa = WakeSet.of(plugins = emptyList(), nativeAlexaInstalled = false)

        val alexa = withAlexa.words.single { it.id == "alexa" }
        assertEquals("hey alexa", alexa.phrase)
        assertEquals(WakeMatcher.STRICT_MIN_CONF, alexa.minConf, 1e-9)
        assertEquals(Consumer.NativeAlexa, withAlexa.target("alexa")?.consumer)
        assertNull(withoutAlexa.target("alexa"))
        assertEquals(listOf("jarvis"), withoutAlexa.words.map { it.id })
    }

    @Test fun aPluginClaimingAlexaOverridesTheNativeAlexaClient() {
        val alexaPlugin = plugin("hey alexa", id = "alexa", minConfidence = "0.6", pkg = "com.example.alexa")

        val set = WakeSet.of(plugins = listOf(alexaPlugin), nativeAlexaInstalled = true)

        assertEquals(
            Consumer.Plugin(packageName = "com.example.alexa", className = "com.example.alexa.WakeReceiver"),
            set.target("alexa")?.consumer,
        )
        assertEquals(listOf("alexa", "jarvis"), set.words.map(WakeWord::id))
    }

    @Test fun installingTheAssistantLeavesTheWordsUnchangedButMakesItJarvisConsumer() {
        val before = WakeSet.of(plugins = emptyList(), nativeAlexaInstalled = false)
        val after = WakeSet.of(plugins = listOf(assistant), nativeAlexaInstalled = false)

        assertEquals(WakeSet.Refresh.Unchanged, before.refresh(after))
        assertEquals(
            Consumer.Plugin(packageName = "com.portal.assistant", className = "com.portal.assistant.WakeReceiver"),
            after.target("jarvis")?.consumer,
        )
        assertEquals(listOf("jarvis"), after.words.map(WakeWord::id))
    }

    @Test fun aChangedPluginPhraseSwapsInTheNewWords() {
        val before = WakeSet.of(plugins = listOf(assistant), nativeAlexaInstalled = false)
        val renamed = plugin("hello jarvis", id = "jarvis", minConfidence = "0.6", pkg = "com.portal.assistant")
        val after = WakeSet.of(plugins = listOf(renamed), nativeAlexaInstalled = false)

        val refresh = before.refresh(after)

        assertEquals(listOf("hello jarvis"), (refresh as WakeSet.Refresh.Swap).words.map(WakeWord::phrase))
    }

    @Test fun reorderedWordsAreUnchanged() {
        val friday = plugin("hey friday", id = "friday", minConfidence = "0.6", pkg = "com.example.friday")
        val computer = plugin("hey computer", id = "computer", minConfidence = "0.6", pkg = "com.example.computer")
        val before = WakeSet.of(plugins = listOf(friday, computer), nativeAlexaInstalled = true)
        val after = WakeSet.of(plugins = listOf(computer, friday), nativeAlexaInstalled = true)

        assertEquals(WakeSet.Refresh.Unchanged, before.refresh(after))
    }

    @Test fun anEmptyWakeSetCannotBeBuilt() {
        assertThrows(IllegalArgumentException::class.java) { WakeSet(emptyList()) }
    }
}
