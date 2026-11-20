package com.ping.verbead.tts

/**
 * What will be read: the text, split into sentences, and where on the screen each sentence is.
 *
 * Built from the lines of the screen. Every line is cleaned on its own and the lines are joined with a
 * line break, which the splitter treats as a sentence boundary, so a sentence never spans two lines.
 * That is what lets a sentence be tied to one box on the screen, for highlighting it and for starting
 * to read from a line the user taps.
 */
class ReadingPlan private constructor(
    val text: String,
    val sentences: List<Sentence>,
    /** The lines that survived cleaning, in reading order; empty for plain text with no position (a selection) */
    val lines: List<ScreenLine>,
    private val lineOfSentence: IntArray,
) {
    val isEmpty: Boolean get() = sentences.isEmpty()

    /** True when the sentences have positions on the screen. */
    val hasPositions: Boolean get() = lines.isNotEmpty()

    /** The line [index] sentence is on, or null for a plan without positions. */
    fun lineIndexOf(index: Int): Int? = lineOfSentence.getOrNull(index)?.takeIf { it >= 0 }

    fun lineOf(index: Int): ScreenLine? = lineIndexOf(index)?.let { lines.getOrNull(it) }

    /** The first sentence of [lineIndex], or null when that line holds none. */
    fun firstSentenceOfLine(lineIndex: Int): Int? = lineOfSentence.indexOfFirst { it == lineIndex }.takeIf { it >= 0 }

    companion object {
        val EMPTY = ReadingPlan("", emptyList(), emptyList(), IntArray(0))

        /** A plan for text with no position, such as a selection. */
        fun fromText(raw: String): ReadingPlan {
            val text = TtsTextCleaner.clean(raw)
            val sentences = SentenceSplitter.split(text)
            return ReadingPlan(text, sentences, emptyList(), IntArray(sentences.size) { -1 })
        }

        /** A plan for the lines of a screen. */
        fun fromLines(raw: List<ScreenLine>): ReadingPlan {
            val kept = ArrayList<ScreenLine>()
            val starts = ArrayList<Int>()
            val sb = StringBuilder()
            for (line in raw) {
                val cleaned = TtsTextCleaner.clean(line.text).replace('\n', ' ')
                if (cleaned.isBlank()) continue
                if (sb.isNotEmpty()) sb.append('\n')
                starts += sb.length
                sb.append(cleaned)
                kept += line.copy(text = cleaned)
            }
            val text = sb.toString()
            val sentences = SentenceSplitter.split(text)
            val lineOf = IntArray(sentences.size) { i -> lineAt(starts, sentences[i].start) }
            return ReadingPlan(text, sentences, kept, lineOf)
        }

        /** The index of the line that holds [offset]: the last line that starts at or before it. */
        private fun lineAt(starts: List<Int>, offset: Int): Int {
            var lo = 0
            var hi = starts.lastIndex
            while (lo < hi) {
                val mid = (lo + hi + 1) ushr 1
                if (starts[mid] <= offset) lo = mid else hi = mid - 1
            }
            return lo
        }
    }
}
