package com.portal.wake.benchmark

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.portal.commons.PcmCaptureFormat
import com.portal.commons.audio.OpenWakeWordDetector
import com.portal.commons.audio.WakeDetector
import com.portal.commons.audio.WakeEvent
import com.portal.commons.audio.WakeHandoffCooldown
import com.portal.commons.audio.WakeWord
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * On-device **file-inject** benchmark — replays a labelled WAV corpus through openWakeWord with no
 * microphone. Host-side matrix tooling pushes clips under `files/bench/corpus/<category>/` and scores
 * `files/bench/results.csv`.
 */
@RunWith(AndroidJUnit4::class)
class WakeBenchmark {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val wakeWord =
        WakeWord.fromPhrase(
            "hey jarvis",
            id = "jarvis",
            scoreThreshold = OpenWakeWordDetector.DEFAULT_SCORE_THRESHOLD.toDouble(),
        )!!

    @Test
    fun benchmark() {
        assertTrue("openWakeWord assets missing", OpenWakeWordDetector.assetsPresent(context))
        val corpus = File(context.getExternalFilesDir(BENCH_DIR), "corpus")
        assertTrue(
            "corpus not found at ${corpus.absolutePath} — run matrix cell C / benchmark/run_bench.sh",
            corpus.isDirectory,
        )
        val clips = corpus.listFiles { f -> f.isDirectory }
            ?.sortedBy { it.name }
            ?.flatMap { cat ->
                (cat.listFiles { f -> f.extension == "wav" } ?: emptyArray())
                    .sorted()
                    .map { cat.name to it }
            }
            ?: emptyList()
        assertTrue("no .wav clips under $corpus", clips.isNotEmpty())
        Log.i(TAG, "corpus: ${clips.size} clips across ${clips.map { it.first }.distinct().size} categories")

        val out = StringBuilder("detector,category,file,duration_ms,fired,fire_count,first_fire_ms,peak_score\n")

        val events = BenchEvents()
        val host = object : WakeDetector.Host {
            override val context: Context = this@WakeBenchmark.context
            override val wakeWords: List<WakeWord> = listOf(wakeWord)
            override val events: WakeDetector.Events = events
            override val handoffCooldown: WakeHandoffCooldown = NoCooldown
        }
        val detector = OpenWakeWordDetector.factory().create(host) as OpenWakeWordDetector
        var peak = 0f
        detector.scoreLogger = { _, s -> if (s > peak) peak = s }
        if (!events.awaitReady()) {
            detector.close()
            error("openWakeWord never became ready (unavailable=${events.unavailable})")
        }
        Log.i(TAG, "detector ready — replaying ${clips.size} clips")
        for ((category, wavFile) in clips) {
            val wav = WavFile.read(wavFile)
            peak = 0f
            val r = replay(detector, events, wav)
            out.append(OpenWakeWordDetector.ID).append(',').append(category).append(',')
                .append(wavFile.name).append(',').append(wav.durationMs).append(',')
                .append(r.fired).append(',').append(r.fireCount).append(',')
                .append(r.firstFireMs?.toString() ?: "").append(',')
                .append("%.3f".format(peak)).append('\n')
            Log.i(
                TAG,
                "[oww] $category/${wavFile.name} dur=${wav.durationMs}ms fired=${r.fired} peak=${"%.3f".format(peak)}",
            )
        }
        detector.close()

        val results = File(context.getExternalFilesDir(BENCH_DIR), "results.csv")
        results.writeText(out.toString())
        Log.i(TAG, "results written to ${results.absolutePath}")
    }

    private data class ClipResult(val fired: Boolean, val fireCount: Int, val firstFireMs: Long?)

    private fun replay(detector: WakeDetector, events: BenchEvents, wav: WavFile): ClipResult {
        events.fires.clear()
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
            events.fires.isNotEmpty(),
            events.fires.size,
            first?.let { it.toLong() * PcmCaptureFormat.FRAME_MS },
        )
    }

    private class BenchEvents : WakeDetector.Events {
        val fires = ArrayList<Int>()
        var currentFrame = 0

        @Volatile var unavailable = false
        private val ready = CountDownLatch(1)

        fun awaitReady(): Boolean = ready.await(READY_TIMEOUT_S, TimeUnit.SECONDS) && !unavailable

        override fun onReady(detectorId: String) = ready.countDown()
        override fun onUnavailable(detectorId: String) {
            unavailable = true
            ready.countDown()
        }

        override fun onWake(event: WakeEvent) {
            fires.add(currentFrame)
            Log.d(TAG, "  fire frame=$currentFrame id=${event.wakeId} [${event.transcript}]")
        }

        override fun onDiagnostic(detectorId: String, message: String) {}
    }

    private object NoCooldown : WakeHandoffCooldown {
        override fun isCoolingDown(wakeId: String): Boolean = false
        override fun isAnyCoolingDown(): Boolean = false
    }

    companion object {
        private const val TAG = "WakeBenchmark"
        private const val BENCH_DIR = "bench"
        private const val READY_TIMEOUT_S = 60L
        private const val TAIL_SILENCE_FRAMES = 12
    }
}
