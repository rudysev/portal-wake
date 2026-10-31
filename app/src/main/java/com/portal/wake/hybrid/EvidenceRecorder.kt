package com.portal.wake.hybrid

import com.portal.commons.DebugLog
import com.portal.commons.PcmCaptureFormat
import com.portal.commons.audio.HybridEvidence
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Writes the hybrid wake detector's [HybridEvidence] under [dir] for review: one JSON object per line in
 * [LOG_FILE] (rotated to `.1` at [maxLogBytes]), and, when [keepAudio], each record's audio as a WAV in [CLIPS_DIR]
 * named after its line's `clip` field. Clips are capped at [maxClipBytes] in all: past it the oldest are deleted, so
 * the directory always holds the most recent days. Every line carries `type`, `at` (ISO-8601 UTC) and `atMs`.
 *
 * **Off the capture thread.** [record] only queues; one daemon thread writes, as [DebugLog] does. Under a flood
 * faster than the disk drains, records are dropped rather than blocking (logged once).
 */
class EvidenceRecorder(
    private val dir: File,
    private val keepAudio: Boolean,
    private val maxClipBytes: Long = DEFAULT_MAX_CLIP_BYTES,
    private val maxLogBytes: Long = DEFAULT_MAX_LOG_BYTES,
    private val log: (String) -> Unit = DebugLog::log,
) {
    private sealed interface Cmd

    private class Write(val evidence: HybridEvidence) : Cmd

    private class Note(val type: String, val atMs: Long, val fields: Map<String, Any?>) : Cmd

    private class Barrier(val done: CountDownLatch) : Cmd

    private val queue = ArrayBlockingQueue<Cmd>(QUEUE_CAPACITY)

    @Volatile private var dropLogged = false

    // Writer-thread state.
    private val clips = File(dir, CLIPS_DIR)
    private var clipBytes = -1L // unknown until the writer first counts the directory

    private val writer = Thread(::runWriter, "wake-evidence").apply {
        isDaemon = true
        start()
    }

    /** Queue [e] for writing, from any thread; never blocks. */
    fun record(e: HybridEvidence) = offer(Write(e))

    /** Queue a line of [type] with [fields] (a session start, a detector change). */
    fun note(type: String, atMs: Long, fields: Map<String, Any?>) = offer(Note(type, atMs, fields))

    /** Stop the writer. Pending records are abandoned: this is teardown, not a flush. */
    fun close() = writer.interrupt()

    /** Block until everything queued before this call is written. **Tests only.** */
    internal fun awaitIdle(timeoutMs: Long = 5_000) {
        val done = CountDownLatch(1)
        check(queue.offer(Barrier(done))) { "queue full" }
        check(done.await(timeoutMs, TimeUnit.MILLISECONDS)) { "evidence writer did not drain in ${timeoutMs}ms" }
    }

    private fun offer(cmd: Cmd) {
        if (queue.offer(cmd) || dropLogged) return
        dropLogged = true
        log("hybrid evidence: queue full, dropping records (disk slower than capture)")
    }

    private fun runWriter() {
        while (true) {
            // The interrupt is close(): leaving the loop is the handling.
            val cmd = try {
                queue.take()
            } catch (
                @Suppress("SwallowedException")
                _: InterruptedException,
            ) {
                return
            }
            runCatching {
                when (cmd) {
                    is Barrier -> cmd.done.countDown()
                    is Write -> write(cmd.evidence)
                    is Note -> line(cmd.type, cmd.atMs, cmd.fields)
                }
            }.onFailure { log("hybrid evidence: write failed ($it)") }
        }
    }

    private fun write(e: HybridEvidence) {
        when (e) {
            is HybridEvidence.Candidate -> line(
                "candidate",
                e.atMs,
                mapOf(
                    "gatePeak" to e.gatePeak.toDouble(),
                    "spotted" to JSONArray(e.spotted),
                    "rival" to e.rival,
                    "vosk" to e.vosk,
                    "voskTranscript" to e.voskTranscript,
                    "woke" to e.woke,
                    "wokeBy" to e.wokeBy,
                    "spotterMs" to e.spotterMs,
                    "voskMs" to e.voskMs,
                    "clip" to clip(if (e.woke) "cand-woke" else "cand", e.atMs, e.audio),
                ),
            )

            is HybridEvidence.VoskWake -> line(
                "vosk_wake",
                e.atMs,
                mapOf(
                    "wakeId" to e.wakeId,
                    "transcript" to e.transcript,
                    "role" to if (e.delivered) "decided" else "shadow",
                    "atHandoff" to e.atHandoff,
                    "clip" to clip(if (e.delivered) "vosk-woke" else "vosk-shadow", e.atMs, e.audio),
                ),
            )

            is HybridEvidence.Disagreement -> line(
                "disagreement",
                e.atMs,
                mapOf(
                    "kind" to e.kind.name.lowercase().replace('_', '-'),
                    "voskAtMs" to e.voskAtMs,
                    "candidateAtMs" to e.candidateAtMs,
                    "candidateVerdict" to e.candidateVerdict,
                ),
            )

            is HybridEvidence.MissMark -> line("miss_mark", e.atMs, mapOf("clip" to clip("miss", e.atMs, e.audio)))

            is HybridEvidence.Stats -> line(
                "stats",
                e.atMs,
                mapOf(
                    "mode" to e.mode.name.lowercase(),
                    "shadow" to e.shadow,
                    "frames" to e.frames,
                    "voskMsMean" to e.voskMsMean,
                    "voskMsMax" to e.voskMsMax,
                    "gateMsMean" to e.gateMsMean,
                    "gateMsMax" to e.gateMsMax,
                    "droppedFrames" to e.droppedFrames,
                    "droppedCandidates" to e.droppedCandidates,
                ),
            )
        }
    }

    private fun line(type: String, atMs: Long, fields: Map<String, Any?>) {
        val o = JSONObject().put("type", type).put("at", Instant.ofEpochMilli(atMs).toString()).put("atMs", atMs)
        fields.forEach { (k, v) -> o.put(k, v ?: JSONObject.NULL) }
        dir.mkdirs()
        val file = File(dir, LOG_FILE)
        if (file.length() > maxLogBytes) file.renameTo(File(dir, "$LOG_FILE.1"))
        file.appendText(o.toString() + "\n")
    }

    /** Write [pcm] as `<kind>_<atMs>.wav` (unless audio is off or empty); its name, or null when none was written. */
    private fun clip(kind: String, atMs: Long, pcm: ByteArray): String? {
        if (!keepAudio || pcm.isEmpty()) return null
        clips.mkdirs()
        if (clipBytes < 0) clipBytes = clips.listFiles().orEmpty().sumOf { it.length() }
        val name = "${kind}_$atMs.wav"
        val part = File(clips, "$name.part")
        part.writeBytes(wavHeader(pcm.size) + pcm)
        check(part.renameTo(File(clips, name))) { "rename $name failed" }
        clipBytes += pcm.size + WAV_HEADER_BYTES
        if (clipBytes > maxClipBytes) prune()
        return name
    }

    /** Delete the oldest clips until the total is back under [maxClipBytes]. */
    private fun prune() {
        val files = clips.listFiles().orEmpty().filter { it.name.endsWith(".wav") }.sortedBy { atMsOf(it.name) }
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= maxClipBytes) break
            val size = f.length()
            if (f.delete()) total -= size
        }
        clipBytes = total
    }

    companion object {
        const val LOG_FILE = "evidence.jsonl"
        const val CLIPS_DIR = "clips"

        /** ~1,500 four-second candidate clips. */
        const val DEFAULT_MAX_CLIP_BYTES = 200L * 1024 * 1024
        const val DEFAULT_MAX_LOG_BYTES = 20L * 1024 * 1024

        private const val QUEUE_CAPACITY = 64
        private const val WAV_HEADER_BYTES = 44
        private const val RIFF_SIZE_OFFSET = 36
        private const val FMT_CHUNK_BYTES = 16
        private const val PCM_FORMAT: Short = 1
        private const val BITS_PER_BYTE = 8

        /** The epoch milliseconds in a clip name (`<kind>_<atMs>.wav`); 0 for a name that has none. */
        internal fun atMsOf(name: String): Long = name.substringAfterLast('_').substringBefore('.').toLongOrNull() ?: 0

        /** A canonical 44-byte WAV header for [dataBytes] of 16 kHz mono 16-bit PCM. */
        internal fun wavHeader(dataBytes: Int): ByteArray {
            val rate = PcmCaptureFormat.SAMPLE_RATE
            val block = PcmCaptureFormat.CHANNELS * PcmCaptureFormat.BYTES_PER_SAMPLE
            return ByteBuffer.allocate(WAV_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray())
                putInt(RIFF_SIZE_OFFSET + dataBytes)
                put("WAVE".toByteArray())
                put("fmt ".toByteArray())
                putInt(FMT_CHUNK_BYTES)
                putShort(PCM_FORMAT)
                putShort(PcmCaptureFormat.CHANNELS.toShort())
                putInt(rate)
                putInt(rate * block)
                putShort(block.toShort())
                putShort((PcmCaptureFormat.BYTES_PER_SAMPLE * BITS_PER_BYTE).toShort())
                put("data".toByteArray())
                putInt(dataBytes)
            }.array()
        }
    }
}
