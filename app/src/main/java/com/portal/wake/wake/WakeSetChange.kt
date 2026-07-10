package com.portal.wake.wake

/**
 * Pure decision for [com.portal.wake.service.WakeService] when the discovered wake set changes.
 * Keeps engine rebuild / capturing-clear rules unit-testable outside the Android service.
 */
internal enum class WakeSetEngineAction {
    /** Words + model assets unchanged — refresh routing only, leave the engine alone. */
    ROUTING_ONLY,

    /** No detectable oWW heads left — tear down the engine. */
    STOP,

    /** First non-empty detectable set — build an engine (arbiter starts capture). */
    BUILD,

    /** Detectable set changed while an engine exists — close, clear capturing, rebuild, reconcile. */
    REBUILD,
}

internal object WakeSetChange {

    fun decide(
        detectableUnchanged: Boolean,
        hasDetectableHeads: Boolean,
        hasEngine: Boolean,
    ): WakeSetEngineAction = when {
        detectableUnchanged -> WakeSetEngineAction.ROUTING_ONLY
        !hasDetectableHeads -> WakeSetEngineAction.STOP
        !hasEngine -> WakeSetEngineAction.BUILD
        else -> WakeSetEngineAction.REBUILD
    }

    /**
     * True when the service must set `capturing = false` before [com.portal.wake.service.MicArbiter.reconcile]
     * (or instead of reconcile on STOP). Required on REBUILD because the closed engine's `onStopped` is
     * ignored after `engineGeneration` bumps.
     */
    fun mustClearCapturing(action: WakeSetEngineAction): Boolean =
        action == WakeSetEngineAction.REBUILD || action == WakeSetEngineAction.STOP
}
