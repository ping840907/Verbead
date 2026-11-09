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
    val trailGapSteps: Int = 0,
    /** probability of each decoded character at the first time step it appears (what [conf] averages) */
    val charProbs: List<Float> = emptyList(),
    /** the dictionary entry of each decoded character ([text] is their concatenation) */
    val chars: List<String> = emptyList(),
    /**
     * The best probability each character reached anywhere in its run of time steps. A character is usually spread over
     * several steps and only peaks in the middle, so this is the number to judge a single character by (the first-step
     * value understates it: correct characters showed 0.5 there).
     */
    val peakProbs: List<Float> = emptyList(),
    /** runner-up candidates at each character's peak step (only when asked for with `topK`), best first */
    val alternatives: List<List<Pair<String, Float>>> = emptyList()
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
        isProtected: (String) -> Boolean = { false },
        topK: Int = 0
    ): RecResult {
        val chars = ArrayList<String>()
        val scores = ArrayList<Float>()
        val peaks = ArrayList<Float>()
        val peakSteps = ArrayList<Int>()
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

            if (maxIndex != 0 && maxIndex == lastIndex && peaks.isNotEmpty() && maxVal > peaks.last()) {
                peaks[peaks.size - 1] = maxVal
                peakSteps[peakSteps.size - 1] = t
            }

            if (maxIndex != 0 && maxIndex != lastIndex) {
                val dictIdx = maxIndex - 1
                if (dictIdx in dict.indices) {
                    chars.add(dict[dictIdx])
                    scores.add(maxVal)
                    peaks.add(maxVal)
                    peakSteps.add(t)
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
                chars.removeAt(0); scores.removeAt(0); peaks.removeAt(0); peakSteps.removeAt(0)
            }
            while (chars.isNotEmpty() && scores.last() < edgeMinProb && !isProtected(chars.last())) {
                chars.removeAt(chars.size - 1); scores.removeAt(scores.size - 1)
                peaks.removeAt(peaks.size - 1); peakSteps.removeAt(peakSteps.size - 1)
            }
        }

        val alternatives = if (topK > 0) {
            peakSteps.mapIndexed { i, step ->
                val base = step * vocab
                val chosen = dict.indexOf(chars[i]) + 1
                // best few entries other than the blank and the chosen character at the peak step
                val best = ArrayList<Pair<Int, Float>>()
                for (c in 1 until vocab) {
                    if (c == chosen) continue
                    val v = probs[base + c]
                    if (best.size < topK) {
                        best.add(c to v)
                        best.sortByDescending { it.second }
                    } else if (v > best.last().second) {
                        best[best.size - 1] = c to v
                        best.sortByDescending { it.second }
                    }
                }
                best.mapNotNull { (c, v) -> dict.getOrNull(c - 1)?.let { it to v } }
            }
        } else emptyList()
        val emitted = chars.size
        val avg = if (emitted > 0) scores.sum() / emitted else 0f
        return RecResult(chars.joinToString(""), avg, emitted, maxGap, innerGap, leadGap, trailGap, scores.toList(), chars.toList(), peaks.toList(), alternatives)
    }
}
