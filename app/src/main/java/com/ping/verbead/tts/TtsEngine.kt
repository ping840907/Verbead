package com.ping.verbead.tts

/**
 * A speech engine that reads utterances one after another. Callbacks must arrive on the main thread.
 * The system engine and the downloaded models (sherpa-onnx) are the implementations.
 */
interface TtsEngine {

    interface Callback {
        fun onStart(utteranceId: Int)
        fun onDone(utteranceId: Int)
        fun onError(utteranceId: Int)
    }

    /** Prepares the engine; [onReady] gets false when speech is not available on this device. */
    fun init(callback: Callback, onReady: (Boolean) -> Unit)

    /** Speaks [text] now; replaces anything still playing or queued. [rate] 1.0 is normal speed. */
    fun speak(utteranceId: Int, text: String, lang: SpeechLang, rate: Float)

    /**
     * Lines [text] up to play right after what is playing now, with nothing but the engine's chosen pause between
     * the two, and returns true. The engine reports [Callback.onDone] for each utterance in turn, so the caller
     * can hand over the next one while the current one is still being spoken. Returns false when the engine
     * cannot do that (then the caller speaks the next sentence when [Callback.onDone] arrives).
     * [stop] and [speak] clear what was queued.
     */
    fun queue(utteranceId: Int, text: String, lang: SpeechLang, rate: Float): Boolean = false

    /**
     * Hints that [text] will be spoken next, so an engine that needs time to prepare speech can do it while the
     * current sentence is still playing. Optional: the default does nothing.
     */
    fun prefetch(text: String, lang: SpeechLang, rate: Float) = Unit

    /** Stops speaking at once and drops what was queued. The engine may still report the stopped utterance as done or failed. */
    fun stop()

    fun shutdown()
}
