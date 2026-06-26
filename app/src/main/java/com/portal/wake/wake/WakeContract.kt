package com.portal.wake.wake

/**
 * The public, stable contract a **wake plugin** (any app that wants its own "hey X") implements with
 * portal-wake.
 *
 * Portal-wake owns the always-on microphone and the recognizer; it knows nothing about any
 * specific assistant (Jarvis, Alexa) or AI model. Instead it *discovers* handlers at runtime: any installed app can
 * advertise one or more wake words by declaring an exported `<receiver>` (or `<service>`) that
 * responds to [ACTION_WAKE] and lists its phrases in a [META_KEYWORDS] meta-data string.
 *
 * Example — an app adding "hey jarvis" that opens its own assistant:
 * ```xml
 * <receiver android:name=".WakeHandoffReceiver" android:exported="true">
 *     <intent-filter>
 *         <action android:name="com.portal.wake.action.WAKE" />
 *     </intent-filter>
 *     <!-- one or more specs separated by '|', each: id;phrase;keyword[;minConf] -->
 *     <meta-data
 *         android:name="com.portal.wake.keywords"
 *         android:value="jarvis;hey jarvis;jarvis;0.55" />
 * </receiver>
 * ```
 * Manifest values must be string literals (XML cannot reference these constants); use these constants
 * in the *code* that reads the broadcast. A plugin does **not** need to depend on portal-wake — the
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

    /** Meta-data key on the handler component carrying its wake specs (see class doc for the format). */
    const val META_KEYWORDS = "com.portal.wake.keywords"

    /** Extra (String) on [ACTION_WAKE]: the matched wake id (e.g. "jarvis"). */
    const val EXTRA_WAKE_ID = "com.portal.wake.extra.ID"
}
