package com.ping.verbead.tts

/**
 * A speech engine that reads one utterance at a time. Callbacks must arrive on the main thread.
 * The system engine is the only implementation for now; a sherpa-onnx one can follow the same shape.
 */
interface TtsEngine {

    interface Callback {
        fun onStart(utteranceId: Int)
        fun onDone(utteranceId: Int)
        fun onError(utteranceId: Int)
    }

    /** Prepares the engine; [onReady] gets false when speech is not available on this device. */
    fun init(callback: Callback, onReady: (Boolean) -> Unit)

    /** Queues [text] for speaking; replaces anything still playing. [rate] 1.0 is normal speed. */
    fun speak(utteranceId: Int, text: String, lang: SpeechLang, rate: Float)

    /** Stops speaking at once. The engine may still report the stopped utterance as done or failed. */
    fun stop()

    fun shutdown()
}
