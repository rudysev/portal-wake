package com.portal.wake.benchmark

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal RIFF/WAVE reader for the benchmark. Parses chunks properly (macOS `afconvert` emits `JUNK`/`FLLR`
 * padding chunks before `data`), validates 16 kHz mono 16-bit LE PCM, and returns the raw `data` bytes.
 */
data class WavFile(val pcm: ByteArray, val sampleRate: Int, val channels: Int, val bitsPerSample: Int) {

    val durationMs: Long get() = (pcm.size.toLong() * 1000) / (sampleRate.toLong() * channels * (bitsPerSample / 8))

    companion object {
        fun read(file: File): WavFile {
            val bytes = file.readBytes()
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            require(bytes.size >= 12) { "too small to be WAV: ${file.name}" }
            require(tag(bb, 0) == "RIFF" && tag(bb, 8) == "WAVE") { "not a RIFF/WAVE file: ${file.name}" }

            var sampleRate = 0
            var channels = 0
            var bits = 0
            var pcm: ByteArray? = null

            var pos = 12
            while (pos + 8 <= bytes.size) {
                val id = tag(bb, pos)
                val size = bb.getInt(pos + 4)
                val body = pos + 8
                when (id) {
                    "fmt " -> {
                        channels = bb.getShort(body + 2).toInt()
                        sampleRate = bb.getInt(body + 4)
                        bits = bb.getShort(body + 14).toInt()
                    }
                    "data" -> {
                        val len = size.coerceAtMost(bytes.size - body)
                        pcm = bytes.copyOfRange(body, body + len)
                    }
                }
                pos = body + size + (size and 1)
            }

            val data = pcm ?: error("no data chunk in ${file.name}")
            return WavFile(data, sampleRate, channels, bits)
        }

        private fun tag(bb: ByteBuffer, at: Int): String =
            String(byteArrayOf(bb.get(at), bb.get(at + 1), bb.get(at + 2), bb.get(at + 3)))
    }
}
