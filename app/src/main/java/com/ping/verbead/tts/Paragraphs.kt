package com.ping.verbead.tts

/**
 * Finds the lines of the screen that are one paragraph wrapped over several lines, so they can be read as the
 * sentences they are and not one sentence per line.
 *
 * A line goes on to the next when it looks like the middle of a paragraph: it does not end a sentence, it
 * runs to the right edge of the text (a short line is a heading, a label or the last line of its paragraph),
 * the next line is where a wrapped line would be (just below, the same size, the same left edge), and it is
 * one piece of text, not a row of columns. Lists of short rows, headings and table rows therefore stay as
 * they are.
 */
object Paragraphs {

    /** The next line must start no further below than this many line heights after the top of the one above... */
    private const val MAX_PITCH = 1.6f

    /** ...and no closer than this (closer is the same line read twice). */
    private const val MIN_PITCH = 0.6f

    /** Line heights may differ by this factor at most (a larger text is a heading). */
    private const val MAX_SIZE_RATIO = 1.4f

    /** Left edges may differ by this many line heights (a first line may be indented). */
    private const val MAX_INDENT = 2.5f

    /** Pieces of a line further apart than this many line heights are columns (a label and its status), not one text. */
    private const val MAX_COLUMN_GAP = 1.5f

    /** A line is "full" when it ends within this many line heights of the right edge of the paragraph. */
    private const val FULL_MARGIN = 3.0f

    /** A line narrower than this many line heights is not the middle of a paragraph. */
    private const val MIN_WIDTH = 5.0f

    private const val TERMINATORS = "。！？!?；;：:…"

    /** The lines of every paragraph, as indices into [lines], in order. Every line is in exactly one paragraph. */
    fun group(lines: List<ScreenLine>): List<List<Int>> {
        val out = ArrayList<MutableList<Int>>()
        var paragraphRight = 0
        for (i in lines.indices) {
            val current = out.lastOrNull()
            if (current != null) {
                val prev = lines[current.last()]
                val right = maxOf(paragraphRight, lines[i].right)
                if (continues(prev, lines[i], right)) {
                    current += i
                    paragraphRight = right
                    continue
                }
            }
            out += mutableListOf(i)
            paragraphRight = lines[i].right
        }
        return out
    }

    /** True when [next] carries on from [prev]; [paragraphRight] is the right edge of the text block they belong to. */
    fun continues(prev: ScreenLine, next: ScreenLine, paragraphRight: Int): Boolean {
        val text = prev.text.trimEnd()
        if (text.isEmpty() || text.last() in TERMINATORS) return false
        if (text.last() == '.' && !text.endsWith("...")) return false

        val hp = (prev.bottom - prev.top).coerceAtLeast(1)
        val hn = (next.bottom - next.top).coerceAtLeast(1)
        val h = maxOf(hp, hn)
        if (h.toFloat() / minOf(hp, hn) > MAX_SIZE_RATIO) return false
        if (prev.gap > MAX_COLUMN_GAP * h || next.gap > MAX_COLUMN_GAP * h) return false // columns of a row, not prose

        val pitch = (next.top - prev.top).toFloat()
        if (pitch < MIN_PITCH * h || pitch > MAX_PITCH * h) return false
        if (kotlin.math.abs(next.left - prev.left) > MAX_INDENT * h) return false
        if (prev.right - prev.left < MIN_WIDTH * h) return false
        return prev.right >= paragraphRight - FULL_MARGIN * h
    }

    /**
     * What goes between [prev] and [next] when they are joined into one text: nothing between Chinese, Japanese
     * or Korean characters, a space between words, and a hyphen at the end of a line is removed when it split a word.
     */
    fun glue(prev: String, next: String): Glue {
        val a = prev.trimEnd()
        val b = next.trimStart()
        if (a.isEmpty() || b.isEmpty()) return Glue("", 0)
        val last = a.last()
        val first = b.first()
        if (isCjk(last) || isCjk(first)) return Glue("", 0)
        if (last == '-' && a.length >= 2 && a[a.length - 2].isLetter() && first.isLowerCase()) return Glue("", 1)
        return Glue(" ", 0)
    }

    /** [separator] goes between the two; [dropFromPrev] characters are first removed from the end of the first. */
    class Glue(val separator: String, val dropFromPrev: Int)

    private fun isCjk(c: Char): Boolean {
        val script = Character.UnicodeScript.of(c.code)
        return script == Character.UnicodeScript.HAN || script == Character.UnicodeScript.HIRAGANA ||
            script == Character.UnicodeScript.KATAKANA || script == Character.UnicodeScript.HANGUL ||
            c.code in 0x3000..0x303F || c.code in 0xFF00..0xFFEF
    }
}
