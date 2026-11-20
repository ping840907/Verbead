package com.ping.verbead.tts

/** A piece of on-screen text and where it is, in screen pixels. */
data class ScreenTextItem(val left: Int, val top: Int, val right: Int, val bottom: Int, val text: String) {
    val centerY: Int get() = (top + bottom) / 2
    val height: Int get() = (bottom - top).coerceAtLeast(1)
}

/**
 * One line of reading: the text of its items joined, and the box that holds them. [gap] is the widest space between
 * two neighbouring pieces it was made of: pieces that touch are one text line that the recognizer cut into boxes
 * (gap near 0), pieces far apart are the columns of a row (a label and its status).
 */
data class ScreenLine(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val text: String,
    val gap: Int = 0,
)

/**
 * Puts text nodes into reading order. The accessibility tree order is the view hierarchy, not what
 * the eye follows; this groups items into lines by vertical position and orders each line left to
 * right, lines top to bottom.
 */
object ReadingOrder {

    /** Items whose vertical centres fall inside the same line band are one line. */
    fun sort(items: List<ScreenTextItem>): List<List<ScreenTextItem>> {
        if (items.isEmpty()) return emptyList()
        val byTop = items.sortedWith(compareBy({ it.top }, { it.left }))
        val lines = ArrayList<MutableList<ScreenTextItem>>()
        val lineBands = ArrayList<IntArray>() // [top, bottom] of the first item of each line
        for (item in byTop) {
            val last = lines.lastOrNull()
            val band = lineBands.lastOrNull()
            if (last != null && band != null && item.centerY in band[0]..band[1]) {
                last += item
            } else {
                lines += mutableListOf(item)
                lineBands += intArrayOf(item.top, item.bottom)
            }
        }
        return lines.map { line -> line.sortedBy { it.left } }
    }

    /** The lines in reading order, each with its text (items joined by a space) and bounding box. */
    fun toLines(items: List<ScreenTextItem>): List<ScreenLine> = sort(items).map { line ->
        val sb = StringBuilder()
        var widest = 0
        for ((i, item) in line.withIndex()) {
            val t = item.text.trim()
            if (i > 0) {
                val prev = line[i - 1]
                val space = item.left - prev.right
                widest = maxOf(widest, space)
                val h = maxOf(prev.height, item.height)
                // pieces that touch and whose join needs no space (Chinese) are one run of text
                val glue = Paragraphs.glue(sb.toString(), t)
                if (space <= TOUCHING * h && glue.separator.isEmpty() && glue.dropFromPrev == 0) {
                    // nothing between
                } else {
                    sb.append(' ')
                }
            }
            sb.append(t)
        }
        ScreenLine(
            left = line.minOf { it.left },
            top = line.minOf { it.top },
            right = line.maxOf { it.right },
            bottom = line.maxOf { it.bottom },
            text = sb.toString(),
            gap = widest,
        )
    }

    /** Pieces closer than this many line heights are one text line cut by the recognizer, not columns. */
    private const val TOUCHING = 0.8f

    /** Lines joined with a line break, items on one line with a space. */
    fun toText(items: List<ScreenTextItem>): String = toLines(items).joinToString("\n") { it.text }
}
