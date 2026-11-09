package com.ping.verbead.engine

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A streaming ("live") recognizer: text appears while the user speaks. Works for every [AsrMode.LIVE] model of the
 * catalog; the model decides where the files are and whether a language option is set on each stream.
 */
class StreamingAsrEngine(private val context: Context, val model: AsrModel) {

    @Volatile private var recognizer: OnlineRecognizer? = null
    private val loadMutex = Mutex()

    /** Provider that successfully initialized the recognizer, e.g. "cpu". */
    var activeProvider: String = "unknown"
        private set

    fun isLoaded() = recognizer != null

    suspend fun load(): EngineLoadResult = loadMutex.withLock {
        if (isLoaded()) return@withLock EngineLoadResult(true, provider = activeProvider)
        withContext(Dispatchers.IO) {
            val files = filesOf()
                ?: return@withContext EngineLoadResult(false, error = "Model files not found: ${model.dir}")
            val encoder = files.encoder
            val decoder = files.decoder
            val joiner = files.joiner
            val tokens = files.tokens

            // A truncated/mismatched download can leave onnxruntime able to "load" a model
            // that never produces real tokens, without ever throwing — dump file sizes and
            // a tokens.txt line count so a bad download is visible without needing another
            // recording test.
            for (path in listOf(encoder, decoder, joiner, tokens)) {
                Log.i(TAG, "model file: $path  size=${File(path).length()} bytes")
            }
            runCatching {
                val lineCount = File(tokens).readLines().size
                Log.i(TAG, "tokens.txt line count (vocab size) = $lineCount")
            }

            release()

            var lastError: Throwable? = null
            for (provider in model.providers) {
                try {
                    recognizer = OnlineRecognizer(
                        config = OnlineRecognizerConfig(
                            featConfig = FeatureConfig(
                                sampleRate = ModelConfig.ASR_SAMPLE_RATE,
                                featureDim = 80,
                            ),
                            modelConfig = OnlineModelConfig(
                                transducer = OnlineTransducerModelConfig(
                                    encoder = encoder,
                                    decoder = decoder,
                                    joiner  = joiner,
                                ),
                                tokens     = tokens,
                                numThreads = model.threads,
                                provider   = provider,
                            ),
                            endpointConfig = EndpointConfig(
                                rule1 = EndpointRule(false, 2.4f, 0f),
                                rule2 = EndpointRule(true,  ModelConfig.vadSilenceSeconds(context), 10f),
                                rule3 = EndpointRule(false, 0f,   20f),
                            ),
                            enableEndpoint = true,
                            decodingMethod = "greedy_search",
                            maxActivePaths = 4,
                        )
                    )
                    activeProvider = provider
                    Log.i(TAG, "Loaded — provider=$provider  threads=${model.threads}")
                    return@withContext EngineLoadResult(true, provider = provider)
                } catch (ex: Throwable) {
                    Log.w(TAG, "Provider '$provider' failed: ${ex.message}", ex)
                    lastError = ex
                }
            }
            EngineLoadResult(false, error = lastError?.message ?: "All providers failed")
        }
    }

    fun release() {
        recognizer?.release()
        recognizer = null
        activeProvider = "unknown"
    }

    private fun filesOf(): AsrModelFiles.Transducer? =
        AsrModelFiles.transducer(ModelConfig.asrModelDir(context, model), model.fileNames)

    fun createStream(): OnlineStream? {
        val stream = recognizer?.createStream() ?: return null
        for ((key, value) in model.streamOptions) stream.setOption(key, value)
        return stream
    }

    fun acceptWaveform(stream: OnlineStream, samples: FloatArray) {
        stream.acceptWaveform(samples, ModelConfig.ASR_SAMPLE_RATE)
    }

    /** Signal end of audio input so the encoder can flush its state. */
    fun inputFinished(stream: OnlineStream) {
        // A model that works in long chunks would drop the last words if the audio just ended,
        // so a little silence is fed first to complete the final chunk.
        if (model.tailSilenceSec > 0f) {
            stream.acceptWaveform(FloatArray((model.tailSilenceSec * ModelConfig.ASR_SAMPLE_RATE).toInt()), ModelConfig.ASR_SAMPLE_RATE)
        }
        stream.inputFinished()
    }

    /** Decode all pending frames in the stream. */
    fun decode(stream: OnlineStream) {
        recognizer?.decode(stream)
    }

    /** Return current partial/final text from the stream. */
    fun getResult(stream: OnlineStream): String =
        recognizer?.getResult(stream)?.text?.trim() ?: ""

    /** True if the endpoint detector has fired (natural pause detected). */
    fun isEndpoint(stream: OnlineStream): Boolean =
        recognizer?.isEndpoint(stream) ?: false

    /** Reset endpoint state so the stream can continue after an endpoint. */
    fun reset(stream: OnlineStream) {
        recognizer?.reset(stream)
    }

    /** True if the stream has enough frames buffered to produce output. */
    fun isReady(stream: OnlineStream): Boolean =
        recognizer?.isReady(stream) ?: false

    companion object {
        private const val TAG = "StreamingAsr"
    }
}
