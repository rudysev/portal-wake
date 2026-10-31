package com.portal.wake.hybrid

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.res.AssetManager
import com.portal.commons.audio.WakeGate
import java.nio.FloatBuffer
import kotlin.random.Random

/**
 * The hybrid wake detector's gate: openWakeWord's streaming pipeline with the `hey_jarvis_v0.1` model, ported from
 * openWakeWord 0.6.0 (`utils.AudioFeatures` and `Model.predict`, ONNX models from release v0.5.1) for
 * [WakeGate.FRAME_SAMPLES]-sample frames. The port is jarvis-wakeword-lab's, which matches openWakeWord itself to
 * within 1e-5 per score:
 *
 * 1. the mel model runs on the frame plus the 480 samples before it (int16 values as floats), and its frames, scaled
 *    `x / 10 + 2`, join a mel buffer that starts as 76 rows of ones;
 * 2. the embedding model turns the newest 76 mel rows into one 96-dim feature, joining a feature buffer that starts
 *    with the embeddings of 4 s of noise in [-1000, 1000);
 * 3. the wake model scores the newest 16 features; the first 5 scores are forced to 0.
 *
 * Every session runs single-threaded (~38 ms per frame on a Portal+). Not thread-safe: the gate thread only.
 */
class OpenWakeWordGate(
    private val env: OrtEnvironment,
    melModel: ByteArray,
    embeddingModel: ByteArray,
    wakeModel: ByteArray,
) : WakeGate {
    private val options = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(1)
        setInterOpNumThreads(1)
    }
    private val mel = env.createSession(melModel, options)
    private val embedding = env.createSession(embeddingModel, options)
    private val wake = env.createSession(wakeModel, options)

    private val raw = ShortArray(MEL_INPUT_SAMPLES)
    private var rawFilled = 0
    private val melRows = ArrayDeque<FloatArray>()
    private val features = ArrayDeque<FloatArray>()
    private var scored = 0

    init {
        repeat(EMBEDDING_WINDOW) { melRows.addLast(FloatArray(MEL_BINS) { 1f }) }
        val noise = warmupNoise()
        val noiseMel = melFrames(FloatArray(noise.size) { noise[it].toFloat() })
        for (start in 0..noiseMel.size - EMBEDDING_WINDOW step EMBEDDING_STEP) {
            features.addLast(embed(noiseMel.subList(start, start + EMBEDDING_WINDOW)))
        }
        while (features.size > WAKE_FEATURES) features.removeFirst()
        check(features.size == WAKE_FEATURES) { "warm-up noise too short: ${noise.size} samples" }
    }

    override fun score(frame: ShortArray): Float {
        require(frame.size == WakeGate.FRAME_SAMPLES) { "frame has ${frame.size} samples" }
        appendRaw(frame)
        val melInput = FloatArray(rawFilled) { raw[MEL_INPUT_SAMPLES - rawFilled + it].toFloat() }
        melRows.addAll(melFrames(melInput))
        while (melRows.size > EMBEDDING_WINDOW) melRows.removeFirst()
        features.addLast(embed(melRows))
        features.removeFirst()
        val score = wakeScore()
        scored++
        return if (scored <= SILENT_SCORES) 0f else score
    }

    /** Keeps the newest [MEL_INPUT_SAMPLES] samples, right-aligned in [raw]. */
    private fun appendRaw(frame: ShortArray) {
        System.arraycopy(raw, frame.size, raw, 0, MEL_INPUT_SAMPLES - frame.size)
        System.arraycopy(frame, 0, raw, MEL_INPUT_SAMPLES - frame.size, frame.size)
        rawFilled = minOf(MEL_INPUT_SAMPLES, rawFilled + frame.size)
    }

    private fun melFrames(samples: FloatArray): List<FloatArray> =
        OnnxTensor.createTensor(env, FloatBuffer.wrap(samples), longArrayOf(1, samples.size.toLong())).use { input ->
            mel.run(mapOf(mel.inputNames.first() to input)).use { out ->
                val values = (out[0] as OnnxTensor).floatBuffer
                List(values.remaining() / MEL_BINS) { FloatArray(MEL_BINS) { values.get() / MEL_SCALE + MEL_OFFSET } }
            }
        }

    private fun embed(window: List<FloatArray>): FloatArray {
        val flat = FloatArray(EMBEDDING_WINDOW * MEL_BINS)
        window.forEachIndexed { i, row -> row.copyInto(flat, i * MEL_BINS) }
        val shape = longArrayOf(1, EMBEDDING_WINDOW.toLong(), MEL_BINS.toLong(), 1)
        return OnnxTensor.createTensor(env, FloatBuffer.wrap(flat), shape).use { input ->
            embedding.run(mapOf(embedding.inputNames.first() to input)).use { out ->
                val values = (out[0] as OnnxTensor).floatBuffer
                FloatArray(FEATURE_DIM) { values.get() }
            }
        }
    }

    private fun wakeScore(): Float {
        val flat = FloatArray(WAKE_FEATURES * FEATURE_DIM)
        features.forEachIndexed { i, row -> row.copyInto(flat, i * FEATURE_DIM) }
        val shape = longArrayOf(1, WAKE_FEATURES.toLong(), FEATURE_DIM.toLong())
        return OnnxTensor.createTensor(env, FloatBuffer.wrap(flat), shape).use { input ->
            wake.run(mapOf(wake.inputNames.first() to input)).use { out ->
                (out[0] as OnnxTensor).floatBuffer.get()
            }
        }
    }

    override fun close() {
        wake.close()
        embedding.close()
        mel.close()
        options.close()
    }

    companion object {
        private const val MEL_BINS = 32
        private const val MEL_SCALE = 10f
        private const val MEL_OFFSET = 2f

        /** The frame plus the 480 (3 × 160) samples before it, as openWakeWord feeds its mel model. */
        private const val MEL_INPUT_SAMPLES = WakeGate.FRAME_SAMPLES + 480
        private const val EMBEDDING_WINDOW = 76
        private const val EMBEDDING_STEP = 8
        private const val FEATURE_DIM = 96

        /** hey_jarvis_v0.1 scores 16 features (1.28 s). */
        private const val WAKE_FEATURES = 16
        private const val SILENT_SCORES = 5
        private const val WARMUP_SAMPLES = 4 * 16_000
        private const val WARMUP_AMPLITUDE = 1_000
        private const val WARMUP_SEED = 1

        /** The asset directory `fetch-hybrid-deps.sh` puts the three models in. */
        const val ASSET_DIR = "hybrid/oww"

        /** openWakeWord's warm-up: 4 s of uniform noise in [-1000, 1000), seeded so runs repeat. */
        private fun warmupNoise(): ShortArray {
            val random = Random(WARMUP_SEED)
            return ShortArray(WARMUP_SAMPLES) { random.nextInt(-WARMUP_AMPLITUDE, WARMUP_AMPLITUDE).toShort() }
        }

        /** The gate on the APK's openWakeWord models. Throws when they are missing (no `fetch-hybrid-deps.sh`). */
        fun fromAssets(assets: AssetManager): OpenWakeWordGate {
            fun read(name: String) = assets.open("$ASSET_DIR/$name").use { it.readBytes() }
            return OpenWakeWordGate(
                OrtEnvironment.getEnvironment(),
                read("melspectrogram.onnx"),
                read("embedding_model.onnx"),
                read("hey_jarvis_v0.1.onnx"),
            )
        }
    }
}
