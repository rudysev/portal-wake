package com.portal.wake.wake

import com.portal.commons.audio.OpenWakeWordDetector
import com.portal.commons.audio.VoskWakeDetector

/**
 * The pure **which-detector-fire-routes** decision, extracted from [com.portal.wake.service.WakeService.onWake]
 * so it can be unit-tested. openWakeWord is the default detector for the word it owns; Vosk covers everything
 * else and is the fallback for that word only when oww is inactive.
 */
object WakeRouting {

    /**
     * Should a fire from [detectorId] (for wake [firedId]) trigger the handoff?
     *  - **oww** always routes — its fixed model only ever fires the word it owns, and it is the default there.
     *  - **Vosk** routes every OTHER word, and is the fallback for the owned word only when oww is inactive
     *    ([owwActive] false or [owwOwnedId] null). When oww is active it owns that word, so Vosk's fire of it is
     *    suppressed (logged shadow) — otherwise the same utterance would hand off twice.
     *  - anything else (a shadow detector) never routes.
     */
    fun shouldRoute(
        detectorId: String,
        firedId: String,
        owwActive: Boolean,
        owwOwnedId: String?,
    ): Boolean = when (detectorId) {
        OpenWakeWordDetector.ID -> true
        VoskWakeDetector.ID -> !(owwActive && owwOwnedId != null && firedId == owwOwnedId)
        else -> false
    }
}
