package com.portal.wake.wake

/**
 * The public, stable contract a **wake plugin** (any app that wants its own "hey X") implements with
 * portal-wake.
 *
 * Portal-wake owns the always-on microphone and the recognizer; it knows nothing about any
 * specific assistant (Jarvis, Alexa) or AI model. Instead it *discovers* handlers at runtime: any installed app can
 * advertise a wake word by declaring an exported `<receiver>` that responds to [ACTION_WAKE] and carries
 * its wake word as named meta-data — one field per setting, so the declaration reads for itself.
 *
 * Example — an app adding "hey jarvis" that opens its own assistant:
 * ```xml
 * <receiver android:name=".WakeHandoffReceiver" android:exported="true">
 *     <intent-filter>
 *         <action android:name="com.portal.wake.action.WAKE" />
 *     </intent-filter>
 *     <meta-data android:name="com.portal.wake.phrase"         android:value="hey jarvis" />
 *     <!-- optional: com.portal.wake.id (defaults to the keyword, here "jarvis") -->
 * </receiver>
 * ```
 *
 * A phrase portal-wake has no bundled model for ships its own openWakeWord classifier, which is what puts
 * it on the accurate two-stage path rather than the Vosk-only fallback:
 * ```xml
 *     <meta-data android:name="com.portal.wake.phrase"          android:value="hey bob" />
 *     <meta-data android:name="com.portal.wake.model"           android:value="oww/hey_bob.onnx" />
 *     <!-- optional: com.portal.wake.score_threshold (defaults to 0.30 — loose on purpose) -->
 * ```
 * [META_PHRASE] is the full spoken phrase: portal-wake takes the **last word as the keyword** and the
 * **word before it as the lead** (e.g. "hey", "hi") — so the lead a user must say lives in the phrase you
 * declare, not in portal-wake ("hi bob" works). One receiver declares **one** wake word; an app that wants
 * several declares several receivers (discovery reads each independently).
 * The meta-data **keys** must be string literals (XML cannot reference these constants); use these
 * constants in the *code* that reads them. The **values** are written as plain text, but note Android
 * type-infers `android:value` — a numeric `min_confidence` like `0.55` is stored as a float — so the
 * reader takes each value type-agnostically (see WakeRegistry). Declare values with `android:value`, not
 * `android:resource` (a resource reference reads back as its numeric id, not the referenced string). A
 * plugin does **not** need to depend on
 * portal-wake — the
 * literal strings are the contract; this object is portal-wake's own typed copy (a first-party plugin
 * may mirror the same literals rather than take a dependency, the way `portal-assistant` does).
 *
 * On a match portal-wake **releases the mic** and sends an **explicit** broadcast of [ACTION_WAKE] to
 * that component with [EXTRA_WAKE_ID] set to the matched id. The handler just acquires the mic
 * (typically via its own foreground service) and holds it for as long as the interaction lasts — which
 * may be a multi-minute, two-way conversation.
 *
 * **The handler does NOT signal when it's done.** Portal-wake reclaims the mic by *detecting* that the
 * consumer has stopped recording (via an `AudioManager.AudioRecordingCallback`) — no "done" broadcast,
 * no reclaim timer. This keeps the contract minimal and works identically for cooperative handlers and
 * uncooperative ones (the native Alexa app, a phone/VoIP call) that could never send a signal.
 * Portal-wake never yanks the mic mid-interaction; it returns only once you release it.
 *
 * **The handler also does NOT signal when it starts on its own** (e.g. a foreground tap). Portal-wake
 * *yields by detection* — the same callback spots a foreign recording session while wake is capturing
 * and stands down automatically. Plugin apps only need to open the mic; no extra broadcast.
 *
 * Nothing here is assistant- or model-specific — that is the point.
 */
object WakeContract {
    /** Action both *discovered* (intent-filter) and *delivered* (explicit broadcast) on a match. */
    const val ACTION_WAKE = "com.portal.wake.action.WAKE"

    /** Required meta-data key: the full spoken phrase, e.g. "hey jarvis" (keyword = last word, lead = the word before). */
    const val META_PHRASE = "com.portal.wake.phrase"

    /** Optional meta-data key: the wake id reported on a match; defaults to the keyword when absent. */
    const val META_ID = "com.portal.wake.id"

    /**
     * Optional meta-data key: the **Vosk** keyword-confidence floor in [[MIN_CONFIDENCE], [MAX_CONFIDENCE]];
     * defaults to the matcher baseline.
     *
     * Applies only on the **Vosk-primary** path — a phrase with no openWakeWord model (see [META_MODEL]),
     * which is detected by grammar decode exactly as every phrase was before openWakeWord existed. A phrase
     * that *does* have a model is detected by the two-stage cascade, where this value has no meaning: it is
     * a Vosk per-word confidence (a real wake decodes at ~1.00), not an openWakeWord probability, and the
     * two are not on the same scale. Use [META_SCORE_THRESHOLD] to tune a model-backed phrase.
     */
    const val META_MIN_CONFIDENCE = "com.portal.wake.min_confidence"

    /**
     * Optional meta-data key: the **openWakeWord** classifier score in [[MIN_CONFIDENCE], [MAX_CONFIDENCE]]
     * this phrase must reach, i.e. its **stage-1** threshold in the two-stage cascade. Defaults to
     * [com.portal.commons.audio.TwoStageTuning.DEFAULT_STAGE1_THRESHOLD] (0.30).
     *
     * The default is deliberately loose. Stage 1 is a recall filter, not the precision gate — a
     * phrase-constrained Vosk decode of the same audio (stage 2) is what rejects speech that merely *scores*
     * like the phrase. Raising this mostly costs recall on far-field and noisy speech, and buys little
     * precision. Raise it only if you ship a [META_MODEL] whose calibration you know differs.
     */
    const val META_SCORE_THRESHOLD = "com.portal.wake.score_threshold"

    /**
     * Optional meta-data key: path to an openWakeWord classifier `.onnx` inside **this plugin's own
     * assets** (e.g. `"oww/hey_bob.onnx"`).
     *
     * Portal-wake bundles models for "hey jarvis" and "hey alexa"; **any other phrase needs one from its
     * plugin**. Without a model the phrase is still detected — it falls back to the Vosk-primary path and
     * behaves exactly as it did before openWakeWord existed, honouring [META_MIN_CONFIDENCE] — but it does
     * not get the cascade's accuracy.
     */
    const val META_MODEL = "com.portal.wake.model"

    /** Inclusive bounds a declared [META_MIN_CONFIDENCE] / [META_SCORE_THRESHOLD] must fall within. */
    const val MIN_CONFIDENCE = 0.0
    const val MAX_CONFIDENCE = 1.0

    /** Extra (String) on [ACTION_WAKE]: the matched wake id (e.g. "jarvis"). */
    const val EXTRA_WAKE_ID = "com.portal.wake.extra.ID"
}
