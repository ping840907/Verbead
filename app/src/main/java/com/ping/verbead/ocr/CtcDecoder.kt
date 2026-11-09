package com.ping.verbead.ocr

/**
 * One decoded recognizer strip.
 *
 * @property emitted number of characters that were actually decoded
 * @property maxGapSteps longest stretch of time steps between two decoded characters (also counting the stretch before
 *   the first and after the last one, up to the end of the real, un-padded strip). A recognizer that does not know a
 *   script silently drops its characters, which shows up here as an unusually long gap.
 */
data class RecResult(
    val text: String,
    val conf: Float,
    val emitted: Int,
    val maxGapSteps: Int,
    /** longest gap between two decoded characters (without the margins) */
    val innerGapSteps: Int = maxGapSteps,
    /** steps before the first / after the last decoded character (includes the crop's own margin) */
    val leadGapSteps: Int = 0,
    val trailGapSteps: Int = 0
) {
    val pair: Pair<String, Float> get() = text to conf
}

/** Greedy CTC decoding of a PP-OCR recognizer output (index 0 is the blank, index i is dictionary entry i - 1). */
object CtcDecoder {
    /**
     * @param edgeMinProb when > 0, a first or last character that is not [isProtected] and whose probability is below
     *   this is dropped. The Korean recognizer sometimes invents a stray punctuation mark or digit at the edge of a
     *   strip (probability 0.17..0.56 in measurements, versus >= 0.94 for 95% of genuine edge characters); dropping those
     *   took a rendered Hangul test set from 92 to 99 of 102 exact lines.
     */
    fun decode(
        probs: FloatArray,
        steps: Int,
        vocab: Int,
        dict: List<String>,
        usableSteps: Int = steps,
        edgeMinProb: Float = 0f,
        isProtected: (String) -> Boolean = { false }
    ): RecResult {
        val chars = ArrayList<String>()
        val scores = ArrayList<Float>()
        var lastIndex = -1
        val usable = usableSteps.coerceIn(1, steps)
        var prevEmitStep = 0
        var maxGap = 0
        var innerGap = 0
        var leadGap = -1

        for (t in 0 until steps) {
            val base = t * vocab
            var maxIndex = 0
            var maxVal = probs[base]
            for (c in 1 until vocab) {
                if (probs[base + c] > maxVal) {
                    maxVal = probs[base + c]
                    maxIndex = c
                }
            }

            if (maxIndex != 0 && maxIndex != lastIndex) {
                val dictIdx = maxIndex - 1
                if (dictIdx in dict.indices) {
                    chars.add(dict[dictIdx])
                    scores.add(maxVal)
                    if (t < usable) {
                        maxGap = maxOf(maxGap, t - prevEmitStep)
                        if (leadGap < 0) leadGap = t else innerGap = maxOf(innerGap, t - prevEmitStep)
                        prevEmitStep = t
                    }
                }
            }
            lastIndex = maxIndex
        }
        maxGap = maxOf(maxGap, usable - prevEmitStep)
        val trailGap = usable - prevEmitStep
        if (leadGap < 0) leadGap = usable

        if (edgeMinProb > 0f) {
            while (chars.isNotEmpty() && scores.first() < edgeMinProb && !isProtected(chars.first())) {
                chars.removeAt(0); scores.removeAt(0)
            }
            while (chars.isNotEmpty() && scores.last() < edgeMinProb && !isProtected(chars.last())) {
                chars.removeAt(chars.size - 1); scores.removeAt(scores.size - 1)
            }
        }
        val emitted = chars.size
        val avg = if (emitted > 0) scores.sum() / emitted else 0f
        return RecResult(chars.joinToString(""), avg, emitted, maxGap, innerGap, leadGap, trailGap)
    }
}
