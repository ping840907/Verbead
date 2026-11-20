package com.ping.verbead.tts

/**
 * What will be read: the text, split into sentences, and where on the screen each sentence is.
 *
 * Built from the lines of the screen. Lines that are one paragraph wrapped over several lines are joined
 * ([Paragraphs]) so they are read as the sentences they are; everything else keeps one line to its own, with
 * a line break between, which the splitter treats as a sentence boundary. A sentence may therefore span
 * several lines of a paragraph (never more than one paragraph), and every sentence knows the lines it covers:
 * that is what lets it be highlighted on the screen and lets a tap on a line start the reading there.
 */
class ReadingPlan private constructor(
    val text: String,
    val sentences: List<Sentence>,
    /** The lines that survived cleaning, in reading order; empty for plain text with no position (a selection) */
    val lines: List<ScreenLine>,
    private val firstLine: IntArray,
    private val lastLine: IntArray,
) {
    val isEmpty: Boolean get() = sentences.isEmpty()

    /** True when the sentences have positions on the screen. */
    val hasPositions: Boolean get() = lines.isNotEmpty()

    /** The first line [index] sentence is on, or null for a plan without positions. */
    fun lineIndexOf(index: Int): Int? = firstLine.getOrNull(index)?.takeIf { it >= 0 }

    fun lineOf(index: Int): ScreenLine? = lineIndexOf(index)?.let { lines.getOrNull(it) }

    /** Every line the sentence covers, in order; empty for a plan without positions. */
    fun linesOf(index: Int): List<ScreenLine> {
        val first = lineIndexOf(index) ?: return emptyList()
        val last = lastLine.getOrNull(index) ?: return emptyList()
        return lines.subList(first, (last + 1).coerceAtMost(lines.size))
    }

    /** The first sentence that covers [lineIndex] (a line in the middle of a sentence belongs to it), or null. */
    fun firstSentenceOfLine(lineIndex: Int): Int? {
        for (i in sentences.indices) if (lineIndex >= firstLine[i] && lineIndex <= lastLine[i] && firstLine[i] >= 0) return i
        return null
    }

    companion object {
        val EMPTY = ReadingPlan("", emptyList(), emptyList(), IntArray(0), IntArray(0))

        /** A plan for text with no position, such as a selection. */
        fun fromText(raw: String): ReadingPlan {
            val text = TtsTextCleaner.clean(raw)
            val sentences = SentenceSplitter.split(text)
            val none = IntArray(sentences.size) { -1 }
            return ReadingPlan(text, sentences, emptyList(), none, none)
        }

        /** A plan for the lines of a screen. */
        fun fromLines(raw: List<ScreenLine>): ReadingPlan {
            val kept = ArrayList<ScreenLine>()
            for (line in raw) {
                val cleaned = TtsTextCleaner.clean(line.text).replace('\n', ' ')
                if (cleaned.isNotBlank()) kept += line.copy(text = cleaned)
            }
            val lineStart = IntArray(kept.size)
            val lineEnd = IntArray(kept.size)
            val sb = StringBuilder()
            for ((g, paragraph) in Paragraphs.group(kept).withIndex()) {
                if (g > 0) sb.append('\n')
                var prevText: String? = null
                for (i in paragraph) {
                    val t = kept[i].text
                    if (prevText != null) {
                        val glue = Paragraphs.glue(prevText, t)
                        sb.setLength(sb.length - glue.dropFromPrev)
                        sb.append(glue.separator)
                    }
                    lineStart[i] = sb.length
                    sb.append(t)
                    lineEnd[i] = sb.length
                    prevText = t
                }
            }
            val text = sb.toString()
            val sentences = SentenceSplitter.split(text)
            val first = IntArray(sentences.size)
            val last = IntArray(sentences.size)
            var from = 0
            for ((n, s) in sentences.withIndex()) {
                while (from < kept.lastIndex && lineEnd[from] <= s.start) from++
                var to = from
                while (to < kept.lastIndex && lineStart[to + 1] < s.end) to++
                first[n] = from
                last[n] = to
            }
            return ReadingPlan(text, sentences, kept, first, last)
        }
    }
}
