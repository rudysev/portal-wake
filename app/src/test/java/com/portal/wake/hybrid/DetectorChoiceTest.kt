package com.portal.wake.hybrid

import com.portal.commons.audio.WakeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DetectorChoiceTest {

    @Test
    fun `the prototype defaults to the hybrid with the Vosk shadow on`() {
        assertEquals(DetectorChoice(WakeMode.HYBRID, shadow = true), DetectorChoice.read(null, null))
    }

    @Test
    fun `a stored choice reads back, and an unreadable one falls back to the default`() {
        assertEquals(DetectorChoice(WakeMode.VOSK, shadow = false), DetectorChoice.read("vosk", false))
        assertEquals(DetectorChoice(), DetectorChoice.read("bogus", "yes"))
    }

    @Test
    fun `a change applies only what it names, and an unknown detector is refused`() {
        val start = DetectorChoice()
        assertEquals(DetectorChoice(WakeMode.VOSK, shadow = true), start.change(" VOSK ", null))
        assertEquals(DetectorChoice(WakeMode.HYBRID, shadow = false), start.change(null, false))
        assertNull(start.change("openwakeword", null))
    }

    @Test
    fun `the choice renders the way debug txt shows it`() {
        assertEquals("hybrid (Vosk shadow off)", DetectorChoice(shadow = false).toString())
        assertEquals("vosk", DetectorChoice(WakeMode.VOSK).toString())
    }
}
