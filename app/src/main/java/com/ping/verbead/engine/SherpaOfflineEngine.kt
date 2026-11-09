package com.ping.verbead.engine

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The offline recognizer for the sherpa-onnx models that need no special options: NeMo Parakeet and Moonshine. */
class SherpaOfflineEngine(private val context: Context, val model: AsrModel) : FinalEngine {

    @Volatile private var recognizer: OfflineRecognizer? = null
    private val loadMutex = Mutex()

    override val loadedHotwords: String get() = ""

    override fun isLoaded() = recognizer != null

    override suspend fun load(hotwords: String): EngineLoadResult = loadMutex.withLock {
        if (isLoaded()) return@withLock EngineLoadResult(true, provider = "cpu")
        withContext(Dispatchers.IO) {
            val dir = ModelConfig.asrModelDir(context, model)
            var lastError: Throwable? = null
            for (provider in model.providers) {
                try {
                    recognizer = OfflineRecognizer(
                        config = OfflineRecognizerConfig(
                            featConfig = FeatureConfig(sampleRate = ModelConfig.ASR_SAMPLE_RATE, featureDim = 80),
                            modelConfig = buildModelConfig(dir, provider),
                        )
                    )
                    Log.i(TAG, "Loaded ${model.id} on $provider")
                    return@withContext EngineLoadResult(true, provider = provider)
                } catch (ex: Throwable) {
                    Log.w(TAG, "Loading ${model.id} on $provider failed: ${ex.message}", ex)
                    lastError = ex
                }
            }
            EngineLoadResult(false, error = lastError?.message ?: "Load failed")
        }
    }

    private fun buildModelConfig(dir: java.io.File, provider: String): OfflineModelConfig = when (model.loader) {
        AsrLoader.MOONSHINE -> {
            val f = AsrModelFiles.moonshine(dir) ?: error("Model files not found: ${model.dir}")
            OfflineModelConfig(
                moonshine = OfflineMoonshineModelConfig(encoder = f.encoder, mergedDecoder = f.mergedDecoder),
                tokens = f.tokens,
                numThreads = model.threads,
                provider = provider,
            )
        }
        AsrLoader.OFFLINE_TRANSDUCER -> {
            val f = AsrModelFiles.transducer(dir, model.fileNames) ?: error("Model files not found: ${model.dir}")
            OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(encoder = f.encoder, decoder = f.decoder, joiner = f.joiner),
                tokens = f.tokens,
                numThreads = model.threads,
                provider = provider,
                modelType = model.offlineModelType ?: "",
            )
        }
        else -> error("${model.loader} is not handled by this engine")
    }

    override fun release() {
        recognizer?.release()
        recognizer = null
    }

    override suspend fun transcribe(samples: FloatArray): String = withContext(Dispatchers.IO) {
        val r = recognizer ?: return@withContext ""
        if (samples.isEmpty()) return@withContext ""
        val t0 = System.currentTimeMillis()
        try {
            val stream = r.createStream()
            try {
                stream.acceptWaveform(samples, ModelConfig.ASR_SAMPLE_RATE)
                r.decode(stream)
                val text = r.getResult(stream).text.trim()
                Log.i(TAG, "${model.id}: ${samples.size / 16000f}s audio -> ${System.currentTimeMillis() - t0} ms: \"$text\"")
                text
            } finally {
                runCatching { stream.release() }
            }
        } catch (ex: Exception) {
            Log.e(TAG, "transcribe error: ${ex.message}", ex)
            ""
        }
    }

    private companion object {
        const val TAG = "SherpaOffline"
    }
}
