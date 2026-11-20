package com.ping.verbead.tts

/** A piece of on-screen text and where it is, in screen pixels. */
data class ScreenTextItem(val left: Int, val top: Int, val right: Int, val bottom: Int, val text: String) {
    val centerY: Int get() = (top + bottom) / 2
    val height: Int get() = (bottom - top).coerceAtLeast(1)
}

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

    /** Lines joined with a line break, items on one line with a space. */
    fun toText(items: List<ScreenTextItem>): String =
        sort(items).joinToString("\n") { line -> line.joinToString(" ") { it.text.trim() } }
}
