package com.portal.wake.benchmark

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.portal.commons.PcmCaptureFormat
import com.portal.commons.audio.WakeDetector
import com.portal.commons.audio.WakeWord
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * On-device openWakeWord stress benchmark — replays a labelled WAV corpus through the detector via file
 * injection (no microphone). Writes per-clip peak scores and fire/no-fire at the shipped threshold;
 * host-side `benchmark/score.py` and `benchmark/offdevice_bench.py report` sweep thresholds offline.
 *
 * Synthetic TTS measures model behaviour, not far-field Portal acoustics — see `benchmark/README.md`.
 */
@RunWith(AndroidJUnit4::class)
class WakeBenchmark {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val wakeWord =
        WakeWord.fromPhrase("hey jarvis", id = "jarvis", scoreThreshold = WakeWord.DEFAULT_SCORE_THRESHOLD)!!

    @Test
    fun benchmark() {
        val corpus = File(context.getExternalFilesDir(BENCH_DIR), "corpus")
        assertTrue(
            "corpus not found at ${corpus.absolutePath} — run benchmark/run_bench.sh (it pushes the corpus)",
            corpus.isDirectory,
        )
        val clips = corpus.listFiles { f -> f.isDirectory }
            ?.sortedBy { it.name }
            ?.flatMap { cat -> (cat.listFiles { f -> f.extension == "wav" } ?: emptyArray()).sorted().map { cat.name to it } }
            ?: emptyList()
        assertTrue("no .wav clips under $corpus", clips.isNotEmpty())
        Log.i(TAG, "corpus: ${clips.size} clips across ${clips.map { it.first }.distinct().size} categories")

        val factory = openWakeWordFactoryOrNull(WakeWord.DEFAULT_SCORE_THRESHOLD.toFloat())
            ?: error("openWakeWord assets missing — build against portal-commons with assets/oww/")

        val (name, detFactory) = factory
        val events = BenchEvents(name)
        val detector = detFactory.create(context, listOf(wakeWord), events)
        assertTrue("openWakeWord never became ready", events.awaitReady())

        Log.i(TAG, "detector '$name' ready — replaying ${clips.size} clips")
        val out = StringBuilder("detector,category,file,duration_ms,fired,fire_count,first_fire_ms,peak_score\n")
        for ((category, wavFile) in clips) {
            val wav = WavFile.read(wavFile)
            val r = replay(detector, events, wav)
            out.append(name).append(',').append(category).append(',').append(wavFile.name).append(',')
                .append(wav.durationMs).append(',').append(r.fired).append(',').append(r.fireCount).append(',')
                .append(r.firstFireMs?.toString() ?: "").append(',').append("%.4f".format(r.peakScore)).append('\n')
            Log.i(TAG, "[$name] $category/${wavFile.name} dur=${wav.durationMs}ms fired=${r.fired} peak=${"%.3f".format(r.peakScore)}")
        }
        detector.close()

        val results = File(context.getExternalFilesDir(BENCH_DIR), "results.csv")
        results.writeText(out.toString())
        Log.i(TAG, "results written to ${results.absolutePath}")
    }

    private data class ClipResult(val fired: Boolean, val fireCount: Int, val firstFireMs: Long?, val peakScore: Float)

    private fun replay(detector: WakeDetector, events: BenchEvents, wav: WavFile): ClipResult {
        events.fires.clear()
        events.peakScore = 0f
        detector.start()
        val frameBytes = PcmCaptureFormat.FRAME_BYTES
        val pcm = wav.pcm
        var idx = 0
        var off = 0
        while (off < pcm.size) {
            val n = minOf(frameBytes, pcm.size - off)
            val frame = if (n == frameBytes) {
                pcm.copyOfRange(off, off + frameBytes)
            } else {
                ByteArray(frameBytes).also { System.arraycopy(pcm, off, it, 0, n) }
            }
            events.currentFrame = idx
            detector.accept(frame, frameBytes)
            idx++
            off += frameBytes
        }
        val silence = ByteArray(frameBytes)
        repeat(TAIL_SILENCE_FRAMES) {
            events.currentFrame = idx
            detector.accept(silence, frameBytes)
            idx++
        }
        val first = events.fires.firstOrNull()
        return ClipResult(
            events.fires.isNotEmpty(), events.fires.size,
            first?.let { it.toLong() * PcmCaptureFormat.FRAME_MS }, events.peakScore,
        )
    }

    private class BenchEvents(private val name: String) : WakeDetector.Events {
        val fires = ArrayList<Int>()
        var currentFrame = 0
        var peakScore = 0f

        @Volatile var unavailable = false
        private val ready = CountDownLatch(1)

        fun awaitReady(): Boolean = ready.await(READY_TIMEOUT_S, TimeUnit.SECONDS) && !unavailable

        override fun onReady(name: String) = ready.countDown()
        override fun onUnavailable(name: String) {
            unavailable = true
            ready.countDown()
        }

        override fun onWake(name: String, id: String, detail: String) {
            fires.add(currentFrame)
            Log.d(TAG, "  fire [$name] frame=$currentFrame id=$id [$detail]")
        }

        override fun onDiagnostic(name: String, message: String) {}

        override fun onScore(name: String, score: Float) {
            if (score > peakScore) peakScore = score
        }
    }

    companion object {
        private const val TAG = "WakeBenchmark"
        private const val BENCH_DIR = "bench"
        private const val READY_TIMEOUT_S = 60L
        private const val TAIL_SILENCE_FRAMES = 12
    }
}
