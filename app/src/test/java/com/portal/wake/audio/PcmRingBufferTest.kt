package com.portal.wake.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmRingBufferTest {

    @Test fun drain_empty_returnsNothing() {
        val buf = PcmRingBuffer(3)
        assertTrue(buf.drain().isEmpty())
    }

    @Test fun drain_returnsFramesInOrder() {
        val buf = PcmRingBuffer(3)
        buf.add(byteArrayOf(1), 1)
        buf.add(byteArrayOf(2, 3), 2)
        val drained = buf.drain()
        assertEquals(2, drained.size)
        assertArrayEquals(byteArrayOf(1), drained[0])
        assertArrayEquals(byteArrayOf(2, 3), drained[1])
        assertTrue(buf.isEmpty())
    }

    @Test fun add_dropsOldestWhenFull() {
        val buf = PcmRingBuffer(2)
        buf.add(byteArrayOf(1), 1)
        buf.add(byteArrayOf(2), 1)
        buf.add(byteArrayOf(3), 1)
        val drained = buf.drain()
        assertEquals(2, drained.size)
        assertArrayEquals(byteArrayOf(2), drained[0])
        assertArrayEquals(byteArrayOf(3), drained[1])
    }

    @Test fun add_copiesPartialLength() {
        val buf = PcmRingBuffer(1)
        val src = byteArrayOf(9, 8, 7)
        buf.add(src, 2)
        src[0] = 0
        val drained = buf.drain()
        assertArrayEquals(byteArrayOf(9, 8), drained.single())
    }
}
