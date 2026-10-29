package com.portal.wake.wake

import com.portal.commons.audio.WakeWord

/**
 * A **wake target**: a wake word paired with its [consumer], decided once at discovery so the handoff only
 * carries it out.
 *
 * @param word     the phrase the recognizer listens for.
 * @param consumer the app woken on a match, or null when none is installed (a match is only logged). The
 *   recognizer never sees it.
 */
data class WakeTarget(val word: WakeWord, val consumer: Consumer?)

/**
 * Who a wake target's match is handed to: a closed set, so the handoff dispatches on it exhaustively.
 *
 * Plain strings, not an Android `ComponentName`: JVM unit tests run with default-valued Android stubs, where
 * component names never compare equal.
 */
sealed interface Consumer {
    /** How discovery's `debug.txt` line names this consumer. */
    val label: String

    /** A wake plugin's receiver for [WakeContract.ACTION_WAKE], named by package and class. */
    data class Plugin(val packageName: String, val className: String) : Consumer {
        override val label: String get() = packageName
    }

    /** The Portal's native Alexa client (`falcon`), woken by its LISTEN broadcast. */
    data object NativeAlexa : Consumer {
        override val label: String = "NativeAlexa"
    }
}
