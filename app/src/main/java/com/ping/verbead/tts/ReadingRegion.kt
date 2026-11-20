package com.ping.verbead.tts

/**
 * The part of the screen that is read: the area that scrolls, which is also the area the reader scrolls.
 *
 * An app's chrome (a browser's address bar and tabs, a toolbar, a banner outside the list) sits outside the
 * scrolling content, so limiting the reading to that area leaves it out without a rule for any one app. It only
 * applies when the scrolling area is a big part of the window; a small carousel or a side list does not make the
 * rest of the screen unreadable.
 */
class ReadingRegion(val left: Int, val top: Int, val right: Int, val bottom: Int) {

    /** True when the middle of [item] lies inside the region. */
    fun contains(item: ScreenTextItem): Boolean {
        val cx = (item.left + item.right) / 2
        val cy = (item.top + item.bottom) / 2
        return cx in left..right && cy in top..bottom
    }

    /** The items inside the region; all of them when none is (the region then tells nothing about this screen). */
    fun filter(items: List<ScreenTextItem>): List<ScreenTextItem> = items.filter { contains(it) }.ifEmpty { items }

    companion object {
        /** The scrolling area must cover at least this share of the window to count as "the content". */
        const val MIN_SHARE = 0.4

        /** The region for a scrolling area [scroll] inside a window [window] (both as l, t, r, b), or null when it is too small. */
        fun of(scroll: IntArray?, window: IntArray): ReadingRegion? {
            if (scroll == null) return null
            val windowArea = area(window)
            if (windowArea <= 0 || area(scroll).toDouble() / windowArea < MIN_SHARE) return null
            return ReadingRegion(scroll[0], scroll[1], scroll[2], scroll[3])
        }

        private fun area(r: IntArray): Long = maxOf(0, r[2] - r[0]).toLong() * maxOf(0, r[3] - r[1])
    }
}
