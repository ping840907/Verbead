package com.ping.verbead.tts

/** One speakable piece of text. [start]/[end] are offsets into the text that was split (end exclusive). */
data class Sentence(val text: String, val start: Int, val end: Int)

/**
 * Splits text into sentences for sentence-by-sentence reading: the next one can be prepared while
 * the current one plays, and the player can highlight, skip and repeat at sentence granularity.
 *
 * Breaks after CJK and Latin terminators and at line breaks. A '.' only ends a sentence when it is
 * followed by whitespace or the end (so "3.14" and "e.g.x" stay whole). Pieces longer than
 * [maxLen] are cut at the last comma-like character, otherwise at [maxLen].
 */
object SentenceSplitter {

    private const val TERMINATORS = "。！？!?；;…\n"
    private const val CLOSERS = "”’」』）)】》〉]\"'"
    private const val SOFT_BREAKS = "，、,:：— "

    fun split(text: String, maxLen: Int = 80): List<Sentence> {
        require(maxLen >= 8) { "maxLen too small" }
        val out = ArrayList<Sentence>()
        var begin = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val ends = when {
                c in TERMINATORS -> true
                c == '.' -> i + 1 >= text.length || text[i + 1].isWhitespace()
                else -> false
            }
            if (ends) {
                var stop = i + 1
                while (stop < text.length && (text[stop] in TERMINATORS && text[stop] != '\n' || text[stop] in CLOSERS)) stop++
                emit(text, begin, stop, maxLen, out)
                begin = stop
                i = stop
            } else {
                i++
            }
        }
        emit(text, begin, text.length, maxLen, out)
        return out
    }

    private fun emit(text: String, from: Int, to: Int, maxLen: Int, out: MutableList<Sentence>) {
        var s = from
        var e = to
        while (s < e && text[s].isWhitespace()) s++
        while (e > s && text[e - 1].isWhitespace()) e--
        if (s >= e || !hasSpeakable(text, s, e)) return
        while (e - s > maxLen) {
            var cut = -1
            for (k in s + maxLen downTo s + maxLen / 2) {
                if (text[k - 1] in SOFT_BREAKS) { cut = k; break }
            }
            if (cut < 0) cut = s + maxLen
            // never split a surrogate pair
            if (cut < e && Character.isLowSurrogate(text[cut]) && Character.isHighSurrogate(text[cut - 1])) cut--
            addTrimmed(text, s, cut, out)
            s = cut
            while (s < e && text[s].isWhitespace()) s++
        }
        addTrimmed(text, s, e, out)
    }

    private fun addTrimmed(text: String, from: Int, to: Int, out: MutableList<Sentence>) {
        var s = from
        var e = to
        while (s < e && text[s].isWhitespace()) s++
        while (e > s && text[e - 1].isWhitespace()) e--
        if (s < e && hasSpeakable(text, s, e)) out += Sentence(text.substring(s, e), s, e)
    }

    /** True when the range holds at least one letter or digit, so punctuation-only pieces are dropped. */
    private fun hasSpeakable(text: String, from: Int, to: Int): Boolean {
        for (k in from until to) if (text[k].isLetterOrDigit()) return true
        return false
    }
}
