package com.ping.verbead.tts

/**
 * Plays a list of sentences through a [TtsEngine], one after another, and keeps track of where it is.
 * Pure Kotlin (no Android types) so it can be unit tested with a fake engine. Use from the main thread.
 *
 * While a sentence plays, the next ones are handed to the engine to queue ([TtsEngine.queue]), so an engine that
 * can play them back to back leaves no gap; when it cannot, the next sentence is spoken as soon as the current
 * one is done. The system engine cannot pause, so pausing stops the current sentence and resuming speaks it
 * again from its start. Every utterance gets a fresh id and callbacks for ids that are no longer wanted are
 * ignored, which is how a skip or a stop silences the sentence that was cut off.
 */
class TtsSession(
    private val engine: TtsEngine,
    private val listener: Listener
) : TtsEngine.Callback {

    enum class State { IDLE, SPEAKING, PAUSED, FINISHED, ERROR }

    interface Listener {
        fun onStateChanged(state: State)
        /** [index] is the sentence now being spoken (also fired while paused when the user skips). */
        fun onSentenceChanged(index: Int)
    }

    var sentences: List<Sentence> = emptyList()
        private set
    var index: Int = 0
        private set
    var state: State = State.IDLE
        private set
    var rate: Float = 1.0f
        set(value) {
            field = value.coerceIn(MIN_RATE, MAX_RATE)
            // the new speed applies from the next sentence; restart the current one so a change is heard now
            if (state == State.SPEAKING) speakCurrent()
        }

    private var utteranceCounter = 0
    private var currentUtterance = -1

    /** Ids of the sentences after [index] that the engine already holds, in order. */
    private val queued = ArrayDeque<Int>()
    private var consecutiveErrors = 0

    /** Loads [list] and starts speaking at [from]. */
    fun start(list: List<Sentence>, from: Int = 0) {
        engine.stop()
        queued.clear()
        sentences = list
        if (list.isEmpty()) {
            setState(State.FINISHED)
            return
        }
        index = from.coerceIn(0, list.lastIndex)
        consecutiveErrors = 0
        listener.onSentenceChanged(index)
        setState(State.SPEAKING)
        speakCurrent()
    }

    fun pause() {
        if (state != State.SPEAKING) return
        currentUtterance = -1
        queued.clear()
        engine.stop()
        setState(State.PAUSED)
    }

    fun resume() {
        when (state) {
            State.PAUSED -> { setState(State.SPEAKING); speakCurrent() }
            State.FINISHED -> if (sentences.isNotEmpty()) {
                index = 0
                listener.onSentenceChanged(index)
                setState(State.SPEAKING)
                speakCurrent()
            }
            else -> Unit
        }
    }

    fun toggle() = if (state == State.SPEAKING) pause() else resume()

    fun next() = skipTo(index + 1)
    fun previous() = skipTo(index - 1)

    fun skipTo(target: Int) {
        if (sentences.isEmpty()) return
        if (target > sentences.lastIndex) {
            if (state == State.SPEAKING) finish()
            return
        }
        index = target.coerceAtLeast(0)
        listener.onSentenceChanged(index)
        if (state == State.SPEAKING) {
            speakCurrent()
        } else if (state == State.FINISHED) {
            // skipping back after the end brings the player back to life, paused on that sentence
            setState(State.PAUSED)
        }
    }

    fun stop() {
        currentUtterance = -1
        queued.clear()
        engine.stop()
        sentences = emptyList()
        index = 0
        setState(State.IDLE)
    }

    private fun speakCurrent() {
        val sentence = sentences.getOrNull(index) ?: return
        queued.clear()
        currentUtterance = ++utteranceCounter
        engine.speak(currentUtterance, sentence.text, LanguageGuess.of(sentence.text), rate)
        fillQueue()
    }

    /** Hands the engine the next sentences to hold; when it cannot queue, gives it a hint for the next one instead. */
    private fun fillQueue() {
        while (queued.size < QUEUE_DEPTH) {
            val at = index + 1 + queued.size
            val s = sentences.getOrNull(at) ?: return
            val id = ++utteranceCounter
            if (!engine.queue(id, s.text, LanguageGuess.of(s.text), rate)) {
                if (queued.isEmpty()) engine.prefetch(s.text, LanguageGuess.of(s.text), rate)
                return
            }
            queued.addLast(id)
        }
    }

    private fun finish() {
        currentUtterance = -1
        queued.clear()
        engine.stop()
        setState(State.FINISHED)
    }

    private fun setState(newState: State) {
        if (state == newState) return
        state = newState
        listener.onStateChanged(newState)
    }

    // ── TtsEngine.Callback ──────────────────────────────────────────────

    override fun onStart(utteranceId: Int) = Unit

    override fun onDone(utteranceId: Int) {
        if (utteranceId != currentUtterance || state != State.SPEAKING) return
        consecutiveErrors = 0
        advance()
    }

    override fun onError(utteranceId: Int) {
        if (state != State.SPEAKING) return
        if (utteranceId != currentUtterance) {
            // a queued sentence failed: the engine goes on with the ones after it, so drop it and what followed
            val at = queued.indexOf(utteranceId)
            if (at >= 0) while (queued.size > at) queued.removeLast()
            return
        }
        // one bad sentence is skipped; a run of them means the engine itself is broken
        if (++consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
            currentUtterance = -1
            queued.clear()
            engine.stop()
            setState(State.ERROR)
            return
        }
        advance()
    }

    private fun advance() {
        if (index >= sentences.lastIndex) {
            finish()
            return
        }
        index++
        listener.onSentenceChanged(index)
        val held = queued.removeFirstOrNull()
        if (held != null) {
            // the engine is already playing it: nothing to speak, only to keep the next ones coming
            currentUtterance = held
            fillQueue()
        } else {
            speakCurrent()
        }
    }

    companion object {
        const val MIN_RATE = 0.5f
        const val MAX_RATE = 2.5f
        private const val MAX_CONSECUTIVE_ERRORS = 3

        /** How many sentences ahead the engine holds: enough that a short sentence before a long one still leaves no gap. */
        private const val QUEUE_DEPTH = 2
    }
}
