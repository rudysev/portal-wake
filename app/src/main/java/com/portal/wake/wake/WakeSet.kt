package com.portal.wake.wake

import com.portal.commons.audio.WakeMatcher
import com.portal.commons.audio.WakeWord

/**
 * The **wake set**: every [WakeTarget] the service listens for, built from the discovered wake plugins and
 * the built-in defaults. Pure (no Android), so its rules are unit-tested.
 *
 * - Defaults: "hey jarvis" (always) and "hey alexa" (only when the native Alexa client is installed), both at
 *   [WakeMatcher.STRICT_MIN_CONF] so the lenient clean-phrase bypass is off; precision comes mainly from the
 *   required "hey". The installed assistant declares the identical jarvis word in its manifest.
 * - A wake plugin always wins over a default with the same id.
 * - **Never empty**: discovery always supplies "jarvis", and constructing an empty set fails.
 */
class WakeSet(targets: List<WakeTarget>) {

    private val targets: List<WakeTarget> = targets.toList()

    init {
        require(this.targets.isNotEmpty()) { "a wake set is never empty" }
    }

    /** The wake words the engine listens for, in discovery order. */
    val words: List<WakeWord> get() = targets.map { it.word }

    /** The wake target for wake [id], or null for an id not in this set. */
    fun target(id: String): WakeTarget? = targets.firstOrNull { it.word.id == id }

    /** One entry per wake target, naming its consumer, for discovery's `debug.txt` line. */
    override fun toString(): String =
        targets.map { "${it.word.id}${if (it.component != null) "→${it.source}" else "(log)"}" }.toString()

    /** What adopting [next] in place of this set means for the engine's grammar. */
    fun refresh(next: WakeSet): Refresh =
        if (words.toSet() == next.words.toSet()) Refresh.Unchanged else Refresh.Swap(next.words)

    /** The outcome of a refresh: the engine either keeps its grammar or swaps in new words. */
    sealed interface Refresh {
        /** Same words (order-insensitive); a wake target's consumer may still have changed. */
        data object Unchanged : Refresh

        /** The words changed: swap the engine's grammar to [words]. */
        data class Swap(val words: List<WakeWord>) : Refresh
    }

    companion object {
        private val BUILTIN_JARVIS = builtin("hey jarvis", id = "jarvis")

        /** The wake set from the discovered wake [plugins] and the built-in defaults. */
        fun of(plugins: List<WakeTarget>, nativeAlexaInstalled: Boolean): WakeSet =
            WakeSet(plugins + defaults(nativeAlexaInstalled).filter { d -> plugins.none { it.word.id == d.word.id } })

        private fun defaults(nativeAlexaInstalled: Boolean): List<WakeTarget> = buildList {
            add(WakeTarget(BUILTIN_JARVIS, component = null, source = null))
            if (nativeAlexaInstalled) {
                add(WakeTarget(builtin("hey alexa", id = "alexa"), component = null, source = null))
            }
        }

        private fun builtin(phrase: String, id: String): WakeWord =
            checkNotNull(WakeWord.fromPhrase(phrase, id = id, minConf = WakeMatcher.STRICT_MIN_CONF)) {
                "built-in phrase \"$phrase\""
            }
    }
}
