package com.ping.verbead.engine

/** An offline ("final") recognizer: it gets the whole recording after the user stops and returns the text. */
interface FinalEngine {
    fun isLoaded(): Boolean

    /** Hot words (one per line) only matter to engines that support them; the others ignore them. */
    suspend fun load(hotwords: String = ""): EngineLoadResult

    /** The hot words the loaded model was built with; engines without hot words always report "". */
    val loadedHotwords: String

    fun release()

    suspend fun transcribe(samples: FloatArray): String
}

/** Builds the offline engine that the model's loader needs. */
object FinalEngines {
    fun create(context: android.content.Context, model: AsrModel): FinalEngine =
        when (model.loader) {
            AsrLoader.QWEN3 -> Qwen3AsrEngine(context)
            AsrLoader.ONLINE_TRANSDUCER -> StreamingAsFinalEngine(context, model)
            else -> SherpaOfflineEngine(context, model)
        }
}

/**
 * Uses a streaming model as a final model: the whole recording is given to the model at once and the text is read
 * after the audio has ended, so the model can be chosen for its accuracy (for example Nemotron with its longest chunk).
 */
class StreamingAsFinalEngine(context: android.content.Context, val model: AsrModel) : FinalEngine {
    private val engine = StreamingAsrEngine(context, model)

    override val loadedHotwords: String get() = ""

    override fun isLoaded() = engine.isLoaded()

    override suspend fun load(hotwords: String): EngineLoadResult = engine.load()

    override fun release() = engine.release()

    override suspend fun transcribe(samples: FloatArray): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (samples.isEmpty()) return@withContext ""
        val stream = engine.createStream() ?: return@withContext ""
        try {
            engine.acceptWaveform(stream, samples)
            engine.inputFinished(stream)
            while (engine.isReady(stream)) engine.decode(stream)
            engine.getResult(stream)
        } catch (ex: Exception) {
            android.util.Log.e("StreamingAsFinal", "transcribe error: ${ex.message}", ex)
            ""
        } finally {
            runCatching { stream.release() }
        }
    }
}
