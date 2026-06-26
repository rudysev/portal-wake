package com.portal.wake.wake

import android.content.ComponentName
import com.portal.wake.audio.WakeWord

/**
 * A wake word paired with what to do when it fires.
 *
 * @param word      the phrase the recognizer listens for.
 * @param component the handler component to broadcast to on a match, or null for a **built-in default**
 *   (no installed handler) — which currently just logs. This is the seam between "recognize" and
 *   "launch": the recognizer never sees the component; the dispatcher routes by [WakeWord.id].
 * @param source    the handler's package id (for logs), or null for a built-in default.
 */
data class WakeTarget(
    val word: WakeWord,
    val component: ComponentName?,
    val source: String?,
)
