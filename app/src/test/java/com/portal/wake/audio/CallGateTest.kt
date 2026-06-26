package com.portal.wake.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stand-down rule: yield the mic whenever the device isn't in MODE_NORMAL. Mode ints match
 * AudioManager: NORMAL=0, RINGTONE=1, IN_CALL=2, IN_COMMUNICATION=3.
 */
class CallGateTest {

    @Test fun normalIsNotInCall() {
        assertFalse(CallGate.inCall(0)) // MODE_NORMAL
    }

    @Test fun ringtoneInCallAndCommunicationAreInCall() {
        assertTrue(CallGate.inCall(1)) // MODE_RINGTONE (incoming ring)
        assertTrue(CallGate.inCall(2)) // MODE_IN_CALL
        assertTrue(CallGate.inCall(3)) // MODE_IN_COMMUNICATION (VoIP, e.g. Messenger)
    }
}
