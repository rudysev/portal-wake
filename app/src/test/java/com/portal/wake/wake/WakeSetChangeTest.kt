package com.portal.wake.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure decision tests for wake-set refresh → engine action (incl. capturing clear on rebuild). */
class WakeSetChangeTest {

    @Test fun unchangedIsRoutingOnly() {
        assertEquals(
            WakeSetEngineAction.ROUTING_ONLY,
            WakeSetChange.decide(detectableUnchanged = true, hasDetectableHeads = true, hasEngine = true),
        )
        assertFalse(WakeSetChange.mustClearCapturing(WakeSetEngineAction.ROUTING_ONLY))
    }

    @Test fun emptyHeadsStopsAndClearsCapturing() {
        assertEquals(
            WakeSetEngineAction.STOP,
            WakeSetChange.decide(detectableUnchanged = false, hasDetectableHeads = false, hasEngine = true),
        )
        assertTrue(WakeSetChange.mustClearCapturing(WakeSetEngineAction.STOP))
    }

    @Test fun firstDetectableSetBuildsWithoutClearing() {
        assertEquals(
            WakeSetEngineAction.BUILD,
            WakeSetChange.decide(detectableUnchanged = false, hasDetectableHeads = true, hasEngine = false),
        )
        assertFalse(WakeSetChange.mustClearCapturing(WakeSetEngineAction.BUILD))
    }

    @Test fun changedSetWithLiveEngineRebuildsAndClearsCapturing() {
        assertEquals(
            WakeSetEngineAction.REBUILD,
            WakeSetChange.decide(detectableUnchanged = false, hasDetectableHeads = true, hasEngine = true),
        )
        assertTrue(
            "REBUILD must clear capturing before reconcile or the mic never restarts",
            WakeSetChange.mustClearCapturing(WakeSetEngineAction.REBUILD),
        )
    }
}
