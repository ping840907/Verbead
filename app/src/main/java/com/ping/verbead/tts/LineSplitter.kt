package com.ping.verbead.tts

/** The box of one character on the screen, in pixels. */
class CharBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val isEmpty: Boolean get() = right <= left || bottom <= top
}

/**
 * Cuts a text node that wraps over several lines into the lines it is drawn in.
 *
 * A web page hands over each piece of a paragraph (a link, a footnote mark, the text between) as a node of its
 * own, and a piece that wraps is one node whose box covers all its lines. Put by position alone, such a piece sits
 * over the pieces next to it and the lines come out in the wrong order. When the screen can say where each
 * character is, the node is cut into its real lines and everything lines up.
 */
object LineSplitter {

    /**
     * True when a node holding [text] and drawn in a [width] x [height] box is probably wrapped over more than one
     * line: it has more characters than one line of that height and width could hold. This only decides whether
     * it is worth asking the screen for the character boxes.
     */
    fun looksMultiLine(text: String, width: Int, height: Int): Boolean {
        val n = text.codePointCount(0, text.length)
        if (n < MIN_CHARS || width <= 0 || height <= 0) return false
        var cjk = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            val script = Character.UnicodeScript.of(cp)
            if (script == Character.UnicodeScript.HAN || script == Character.UnicodeScript.HIRAGANA ||
                script == Character.UnicodeScript.KATAKANA || script == Character.UnicodeScript.HANGUL
            ) cjk++
        }
        // one character is about as wide as a line is high in CJK text, about half as wide in Latin text
        val charWidthPerHeight = if (cjk * 2 > n) 1.0 else 0.5
        return n * height * charWidthPerHeight > SLACK * width
    }

    /**
     * The lines of [text], one item per line, from the boxes of its characters ([boxes] has an entry per UTF-16
     * unit of [text]; a space or a line break has none). A character belongs to the line whose height its centre
     * is within. Returns null when the text is on one line (nothing to cut) or has no boxes at all.
     */
    fun split(text: String, boxes: List<CharBox?>): List<ScreenTextItem>? {
        val items = ArrayList<ScreenTextItem>()
        var start = -1
        var end = 0
        var left = 0
        var top = 0
        var right = 0
        var bottom = 0

        fun flush() {
            if (start < 0) return
            val piece = text.substring(start, end).trim()
            if (piece.isNotEmpty()) items += ScreenTextItem(left, top, right, bottom, piece)
            start = -1
        }

        for (i in text.indices) {
            val b = boxes.getOrNull(i) ?: continue
            if (b.isEmpty) continue
            val centre = (b.top + b.bottom) / 2
            if (start < 0) {
                start = i; end = i + 1
                left = b.left; top = b.top; right = b.right; bottom = b.bottom
            } else if (centre in top..bottom) {
                end = i + 1
                left = minOf(left, b.left); top = minOf(top, b.top); right = maxOf(right, b.right); bottom = maxOf(bottom, b.bottom)
            } else {
                flush()
                start = i; end = i + 1
                left = b.left; top = b.top; right = b.right; bottom = b.bottom
            }
        }
        flush()
        return if (items.size >= 2) items else null
    }

    private const val MIN_CHARS = 8

    /** A node is taken for wrapped only when it is clearly too long for one line, not at the edge of it. */
    private const val SLACK = 1.3
}
