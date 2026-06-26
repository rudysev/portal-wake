package com.portal.wake.wake

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The wake contract strings are a **published API**: plugin apps hard-code these exact literals in
 * their manifests, and the wake app broadcasts them. Changing a value silently breaks every plugin, so
 * this test locks the wire format. If you intend to change the contract, change it here deliberately.
 */
class WakeContractTest {
    @Test fun actionIsStable() {
        assertEquals("com.portal.wake.action.WAKE", WakeContract.ACTION_WAKE)
    }

    @Test fun metaKeyIsStable() {
        assertEquals("com.portal.wake.keywords", WakeContract.META_KEYWORDS)
    }

    @Test fun extraIdIsStable() {
        assertEquals("com.portal.wake.extra.ID", WakeContract.EXTRA_WAKE_ID)
    }
}
