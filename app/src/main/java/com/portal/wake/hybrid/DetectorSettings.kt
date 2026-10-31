package com.portal.wake.hybrid

import android.content.Context
import android.content.SharedPreferences
import com.portal.commons.audio.WakeMode

/**
 * Which wake detector decides, and whether the Vosk shadow runs beside the hybrid: [DetectorChoice] kept in this app's
 * own SharedPreferences, so it survives restarts and reboots, and changed over adb without reinstalling
 * (`ACTION_SET_DETECTOR` in `WakeService`). The engine reads [mode] and [shadow] at every frame, so a change applies
 * at once. Written on the main thread, read from the capture thread.
 */
class DetectorSettings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Volatile var choice: DetectorChoice = DetectorChoice.read(prefs.getString(KEY_MODE, null), prefs.all[KEY_SHADOW])
        private set

    fun mode(): WakeMode = choice.mode

    fun shadow(): Boolean = choice.shadow

    /** Apply a change from adb and persist it; returns the line to log (the new choice, or why it was refused). */
    fun change(detector: String?, shadow: Boolean?): String {
        val next = choice.change(detector, shadow) ?: return "detector change refused: unknown detector '$detector'"
        choice = next
        prefs.edit().putString(KEY_MODE, next.mode.name.lowercase()).putBoolean(KEY_SHADOW, next.shadow).apply()
        return "detector → $next"
    }

    private companion object {
        const val PREFS = "wake-detector"
        const val KEY_MODE = "detector"
        const val KEY_SHADOW = "shadow"
    }
}

/**
 * The detector choice: [mode], and [shadow] (only meaningful in hybrid mode). The prototype's default is the hybrid
 * with the Vosk shadow on.
 */
data class DetectorChoice(val mode: WakeMode = WakeMode.HYBRID, val shadow: Boolean = true) {
    /** This choice with [detector] (`hybrid` / `vosk`, any case) and [shadow] applied; null on an unknown detector. */
    fun change(detector: String?, shadow: Boolean?): DetectorChoice? {
        val mode = if (detector == null) mode else parseMode(detector) ?: return null
        return DetectorChoice(mode, shadow ?: this.shadow)
    }

    override fun toString() = when (mode) {
        WakeMode.HYBRID -> "hybrid (Vosk shadow ${if (shadow) "on" else "off"})"
        WakeMode.VOSK -> "vosk"
    }

    companion object {
        private fun parseMode(s: String): WakeMode? = WakeMode.entries.firstOrNull { it.name.equals(s.trim(), true) }

        /** The stored choice; anything missing or unreadable falls back to the default. */
        fun read(mode: String?, shadow: Any?): DetectorChoice {
            val default = DetectorChoice()
            return DetectorChoice(mode?.let(::parseMode) ?: default.mode, shadow as? Boolean ?: default.shadow)
        }
    }
}
