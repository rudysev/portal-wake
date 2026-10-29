package com.portal.wake.system

/**
 * The Portal's native Alexa client app (the `NativeAlexa` consumer). The built-in "alexa" wake target drives it
 * directly: [WakeRegistry] only advertises "hey alexa" when [PACKAGE] is installed, and [WakeHandoff] hands the
 * mic to it by broadcasting [LISTEN_ACTION]. Single source for these ids so the two files can't drift
 * apart on which app the handoff wakes.
 *
 * Distinct from `com.millennium` (the native "Hey Alexa" *detector* that [MicLiberator] suppresses):
 * falcon is the client we keep alive and invoke, never a mic competitor.
 */
internal object Falcon {
    const val PACKAGE = "com.amazon.alexa.multimodal.falcon"
    const val LISTEN_ACTION = "com.amazon.alexa.multimodal.falcon.LISTEN"
}
