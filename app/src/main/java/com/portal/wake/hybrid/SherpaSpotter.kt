package com.portal.wake.hybrid

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.portal.commons.PcmCaptureFormat
import com.portal.commons.audio.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotter as SherpaKeywordSpotter

/**
 * The hybrid wake detector's keyword spotter: sherpa-onnx's open-vocabulary spotter (the 3.3M Zipformer, int8) on the
 * APK's `hybrid/kws/` model, with the **competing keywords** in [KEYWORDS_ASSET]: "HEY JARVIS" plus the sound-alikes
 * that most often woke Vosk on the Portal+ (OK/Okay/Hi Jarvis, Hey Jervis), so one of those is named as a rival
 * rather than confirmed as the wake. Threshold [THRESHOLD] and boost [BOOST] are the values the hybrid was measured
 * with. Not thread-safe: the verify thread only.
 */
class SherpaSpotter private constructor(assets: AssetManager) : KeywordSpotter {
    private val kws = SherpaKeywordSpotter(
        assets,
        KeywordSpotterConfig(
            featConfig = FeatureConfig(sampleRate = PcmCaptureFormat.SAMPLE_RATE, featureDim = FEATURE_DIM),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = "$MODEL_DIR/$ENCODER",
                    decoder = "$MODEL_DIR/$DECODER",
                    joiner = "$MODEL_DIR/$JOINER",
                ),
                tokens = "$MODEL_DIR/$TOKENS",
                numThreads = 1,
                debug = false,
            ),
            keywordsFile = KEYWORDS_ASSET,
            keywordsThreshold = THRESHOLD,
            keywordsScore = BOOST,
        ),
    )

    override fun spot(samples: FloatArray): List<String> {
        val stream = kws.createStream()
        try {
            stream.acceptWaveform(samples, PcmCaptureFormat.SAMPLE_RATE)
            stream.inputFinished()
            val spotted = mutableListOf<String>()
            while (kws.isReady(stream)) {
                kws.decode(stream)
                val keyword = kws.getResult(stream).keyword
                if (keyword.isNotEmpty()) {
                    spotted += keyword
                    kws.reset(stream)
                }
            }
            return spotted
        } finally {
            stream.release()
        }
    }

    override fun close() = kws.release()

    companion object {
        private const val FEATURE_DIM = 80
        private const val THRESHOLD = 0.15f
        private const val BOOST = 1.5f
        private const val MODEL_DIR = "hybrid/kws"
        private const val ENCODER = "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
        private const val DECODER = "decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
        private const val JOINER = "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
        private const val TOKENS = "tokens.txt"
        const val KEYWORDS_ASSET = "hybrid/hey_jarvis_compete.txt"

        /**
         * The spotter on the APK's model. Every file is opened first, so a build without `fetch-hybrid-deps.sh`'s
         * models throws here: sherpa-onnx's native loader would end the process on a missing file instead.
         */
        fun fromAssets(assets: AssetManager): SherpaSpotter {
            (listOf(ENCODER, DECODER, JOINER, TOKENS).map { "$MODEL_DIR/$it" } + KEYWORDS_ASSET)
                .forEach { assets.open(it).close() }
            return SherpaSpotter(assets)
        }
    }
}
