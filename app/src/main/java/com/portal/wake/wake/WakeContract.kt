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
 *     <meta-data android:name="com.portal.wake.min_confidence" android:value="0.55" />
 *     <meta-data android:name="com.portal.wake.model"         android:value="oww/hey_jarvis.onnx" />
 *     <!-- optional: com.portal.wake.id (defaults to the keyword, here "jarvis") -->
 * </receiver>
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

    /** Optional meta-data key: the detection threshold in [[MIN_CONFIDENCE], [MAX_CONFIDENCE]]; defaults to [WakeWord.DEFAULT_MIN_CONF]. */
    const val META_MIN_CONFIDENCE = "com.portal.wake.min_confidence"

    /**
     * Optional meta-data key: path to an openWakeWord classifier `.onnx` inside **this plugin's assets**
     * (e.g. `"oww/hey_jarvis.onnx"`). Required for custom wake words — built-in "hey jarvis" and "hey alexa"
     * ship bundled models in portal-wake; any other phrase needs a plugin-supplied model. See README.
     */
    const val META_MODEL = "com.portal.wake.model"

    /** Inclusive bounds a declared [META_MIN_CONFIDENCE] must fall within. */
    const val MIN_CONFIDENCE = 0.0
    const val MAX_CONFIDENCE = 1.0

    /** Extra (String) on [ACTION_WAKE]: the matched wake id (e.g. "jarvis"). */
    const val EXTRA_WAKE_ID = "com.portal.wake.extra.ID"
}
