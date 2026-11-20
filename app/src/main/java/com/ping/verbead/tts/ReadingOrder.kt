package com.ping.verbead.tts

/** A piece of on-screen text and where it is, in screen pixels. */
data class ScreenTextItem(val left: Int, val top: Int, val right: Int, val bottom: Int, val text: String) {
    val centerY: Int get() = (top + bottom) / 2
    val height: Int get() = (bottom - top).coerceAtLeast(1)
}

/** One line of reading: the text of its items joined, and the box that holds them. */
data class ScreenLine(val left: Int, val top: Int, val right: Int, val bottom: Int, val text: String)

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
        ScreenLine(
            left = line.minOf { it.left },
            top = line.minOf { it.top },
            right = line.maxOf { it.right },
            bottom = line.maxOf { it.bottom },
            text = line.joinToString(" ") { it.text.trim() },
        )
    }

    /** Lines joined with a line break, items on one line with a space. */
    fun toText(items: List<ScreenTextItem>): String = toLines(items).joinToString("\n") { it.text }
}
