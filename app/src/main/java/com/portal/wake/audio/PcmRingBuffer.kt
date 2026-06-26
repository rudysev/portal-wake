package com.portal.wake.audio

/**
 * Fixed-capacity FIFO of recent PCM frames. Used to retain speech spoken while the Vosk model is still
 * loading so it can be fed to the recognizer once ready (instead of being discarded).
 */
internal class PcmRingBuffer(private val maxFrames: Int) {

    private val frames = ArrayDeque<ByteArray>()

    /** Append a frame (copied). Drops the oldest frame when at capacity. */
    @Synchronized
    fun add(data: ByteArray, n: Int) {
        val frame = if (n == data.size) data.copyOf() else data.copyOf(n)
        frames.addLast(frame)
        while (frames.size > maxFrames) frames.removeFirst()
    }

    /** Remove and return all buffered frames in insertion order. */
    @Synchronized
    fun drain(): List<ByteArray> {
        if (frames.isEmpty()) return emptyList()
        val copy = frames.toList()
        frames.clear()
        return copy
    }

    @Synchronized
    fun isEmpty(): Boolean = frames.isEmpty()
}
