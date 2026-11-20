package com.ping.verbead.tts

/**
 * Decides when the text the accessibility tree gave is too little to read and the screen should be
 * recognized as an image instead (games, pictures, scans, canvas-drawn apps).
 *
 * The numbers come from measuring real screens (TTS_PHASE0.md): a web page gave 422 characters over 96% of
 * the screen, a contacts list 256 over 56%. Anything with next to no text, or little text on little of the
 * screen, is a screen the tree cannot describe.
 */
object OcrFallbackPolicy {

    private const val GRID_COLS = 48
    private const val GRID_ROWS = 96

    /** Fewer characters than this is "no text". */
    const val MIN_CHARS = 20

    /** Below this share of the screen covered by text, a modest amount of text may be just captions on a picture. */
    const val MIN_COVERAGE = 0.15f

    /** ...and "modest" means fewer characters than this. */
    const val MODEST_CHARS = 120

    /** Share (0..1) of a coarse grid over the screen that at least one text item touches. */
    fun coverage(items: List<ScreenTextItem>, screenWidth: Int, screenHeight: Int): Float {
        if (items.isEmpty() || screenWidth <= 0 || screenHeight <= 0) return 0f
        val grid = BooleanArray(GRID_COLS * GRID_ROWS)
        for (item in items) {
            val x0 = cell(item.left, screenWidth, GRID_COLS)
            val x1 = cell(item.right, screenWidth, GRID_COLS)
            val y0 = cell(item.top, screenHeight, GRID_ROWS)
            val y1 = cell(item.bottom, screenHeight, GRID_ROWS)
            for (y in y0..maxOf(y0, y1)) for (x in x0..maxOf(x0, x1)) grid[y * GRID_COLS + x] = true
        }
        return grid.count { it }.toFloat() / grid.size
    }

    private fun cell(v: Int, size: Int, cells: Int): Int = (v.toLong() * cells / size).toInt().coerceIn(0, cells - 1)

    fun needsOcr(chars: Int, coverage: Float): Boolean =
        chars < MIN_CHARS || (coverage < MIN_COVERAGE && chars < MODEST_CHARS)
}
