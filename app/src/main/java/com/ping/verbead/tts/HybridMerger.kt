package com.ping.verbead.tts

/**
 * Combines the text the accessibility tree gives with the text recognized from a screenshot, so each
 * makes up for what the other lacks:
 *
 *  - Where the tree has the text, the tree's text is used. It is exact, so a recognized line that says the
 *    same thing is dropped, which is how recognition mistakes are replaced by the right characters.
 *  - Where the tree has nothing (text inside a picture, a canvas, a video frame), the recognized line is
 *    added at its place on the screen.
 *
 * A recognized line "says the same thing" when it sits on tree items and most of its character pairs are
 * found in their text ([TextSimilarity.containment] at least [MATCH]). A line over a tree item that says
 * something else (a caption over a picture that has its own writing) is kept as well.
 */
object HybridMerger {

    /** Share of a recognized line's character pairs that must be found in the tree text for the two to count as the same. */
    const val MATCH = 0.5f

    /** A tree item and a recognized line are on the same spot when this much of the line is inside the item... */
    private const val OVERLAP = 0.4f

    /** A recognized line with fewer letters or digits than this is noise (a stray glyph) and is never added. */
    const val MIN_INSERT_CHARS = 2

    class Result(
        /** The tree's items plus the recognized lines that were added. */
        val items: List<ScreenTextItem>,
        /** Recognized lines added because the tree had no matching text there. */
        val inserted: Int,
        /** Recognized lines dropped because the tree already had that text. */
        val matched: Int,
    )

    fun merge(tree: List<ScreenTextItem>, recognized: List<ScreenTextItem>): Result {
        val out = ArrayList<ScreenTextItem>(tree)
        var inserted = 0
        var matched = 0
        for (ocr in recognized) {
            val over = tree.filter { onSameSpot(ocr, it) }
            val treeText = over.sortedWith(compareBy({ it.top }, { it.left })).joinToString(" ") { it.text }
            if (over.isNotEmpty() && TextSimilarity.containment(ocr.text, treeText) >= MATCH) {
                matched++
            } else if (TextSimilarity.letterCount(ocr.text) >= MIN_INSERT_CHARS) {
                out += ocr
                inserted++
            }
        }
        return Result(out, inserted, matched)
    }

    /** The recognized line's centre is inside the tree item, or a large enough part of the line is. */
    private fun onSameSpot(ocr: ScreenTextItem, node: ScreenTextItem): Boolean {
        val cx = (ocr.left + ocr.right) / 2
        val cy = ocr.centerY
        if (cx in node.left..node.right && cy in node.top..node.bottom) return true
        val w = minOf(ocr.right, node.right) - maxOf(ocr.left, node.left)
        val h = minOf(ocr.bottom, node.bottom) - maxOf(ocr.top, node.top)
        if (w <= 0 || h <= 0) return false
        val area = (ocr.right - ocr.left).toLong() * (ocr.bottom - ocr.top)
        return area > 0 && w.toLong() * h >= OVERLAP * area
    }
}
