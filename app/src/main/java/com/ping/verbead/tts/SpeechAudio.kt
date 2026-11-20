package com.ping.verbead.tts

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Shapes synthesized speech for sentence-to-sentence playback.
 *
 * A speech model pads each utterance with some silence (Melo about 50-110 ms before and up to 140 ms after,
 * Kokoro about 150 ms after), and the gap that makes between two sentences is longer than a person leaves.
 * The silence is cut off and replaced by a pause of a chosen length, so the pause between sentences is
 * one the player controls, not an accident of the model.
 */
object SpeechAudio {

    /** What counts as silence: this share of the utterance's loudest sample, but never less than [FLOOR]. */
    const val SILENCE_RATIO = 0.02f
    const val FLOOR = 0.003f

    /** Speech this close to the cut is kept: the soft start of a word and the tail of the last sound. */
    const val KEEP_BEFORE_MS = 12
    const val KEEP_AFTER_MS = 25

    /** The ends are faded over this long so the cut makes no click. */
    const val FADE_MS = 6

    /** Pause after a sentence that ends with a full stop, question or exclamation mark... */
    const val PAUSE_SENTENCE_MS = 180

    /** ...after one that ends with a semicolon or colon... */
    const val PAUSE_CLAUSE_MS = 120

    /** ...and after anything else (a line of a list, a heading with no punctuation). */
    const val PAUSE_OTHER_MS = 90

    fun pauseMs(text: String): Int {
        val last = text.trimEnd().lastOrNull { !it.isWhitespace() && it !in CLOSERS } ?: return PAUSE_OTHER_MS
        return when (last) {
            '。', '！', '？', '.', '!', '?', '…' -> PAUSE_SENTENCE_MS
            '；', ';', '：', ':', '，', ',', '、' -> PAUSE_CLAUSE_MS
            else -> PAUSE_OTHER_MS
        }
    }

    private const val CLOSERS = "”’」』）)】》〉]\"'"

    /**
     * [samples] without the silence at both ends and with [pauseMs] of silence added after it. Audio that is
     * silent all through is returned as just the pause. The input is not changed.
     */
    fun shape(samples: FloatArray, sampleRate: Int, pauseMs: Int): FloatArray {
        val pauseFrames = frames(pauseMs, sampleRate)
        var peak = 0f
        for (x in samples) peak = max(peak, abs(x))
        val limit = max(FLOOR, peak * SILENCE_RATIO)
        var first = samples.indexOfFirst { abs(it) > limit }
        if (first < 0) return FloatArray(pauseFrames)
        var last = samples.indexOfLast { abs(it) > limit }
        first = max(0, first - frames(KEEP_BEFORE_MS, sampleRate))
        last = min(samples.lastIndex, last + frames(KEEP_AFTER_MS, sampleRate))
        val core = last - first + 1
        val out = FloatArray(core + pauseFrames)
        System.arraycopy(samples, first, out, 0, core)
        val fade = min(frames(FADE_MS, sampleRate), core / 2)
        for (i in 0 until fade) {
            val g = i.toFloat() / fade
            out[i] *= g
            out[core - 1 - i] *= g
        }
        return out
    }

    private fun frames(ms: Int, sampleRate: Int): Int = (ms.toLong() * sampleRate / 1000).toInt()
}
