package com.ping.verbead.tts

/**
 * Decides where to start reading on a page that was reached by scrolling on from the one just read.
 *
 * A scroll rarely moves by exactly one page, so the top of the new page repeats the end of the old one, and a
 * line that was cut by the edge of the screen shows up again whole (or the other way round). Those sentences
 * were read already: reading starts after them. A sentence counts as read when most of its text is found in
 * a sentence of the old page, which also covers a line that is only partly visible, and a recognition mistake.
 *
 * Taking a sentence for a repeat when it is not is the worse mistake (it is skipped), and numbered sentences
 * ("Chapter 3 ...", "Chapter 4 ...") are almost the same text. So the numbers in a sentence must match too.
 * Sentences that differ only in a spelled-out number or one word ("the first ...", "the second ...") can still
 * be mistaken for each other.
 */
object PageContinuation {

    /** Share of a sentence that must be found in an old sentence for it to count as read. */
    private const val SEEN = 0.85f

    /** A sentence shorter than this (letters and digits) says too little to tell a repeat from a coincidence. */
    private const val MIN_CHARS = 6

    /** The overlap is the block at the top: it ends after this many sentences in a row that were not read before. */
    private const val MAX_GAP = 2

    /** If none of the first sentences was read before, the pages do not overlap. */
    private const val LOOK_AHEAD = 3

    /**
     * The index in [next] to start from, or null when [next] holds nothing that was not read already (the end of
     * the content: scrolling no longer brings anything new). [read] are the sentences of the page before.
     */
    fun firstNew(read: List<String>, next: List<Sentence>): Int? {
        if (next.isEmpty()) return null
        val old = read.filter { TextSimilarity.letterCount(it) >= MIN_CHARS }
        fun seen(text: String) = TextSimilarity.letterCount(text) >= MIN_CHARS &&
            old.any { sameNumbers(text, it) && TextSimilarity.containment(text, it) >= SEEN }

        var lastSeen = -1
        var misses = 0
        for (i in next.indices) {
            if (seen(next[i].text)) {
                lastSeen = i
                misses = 0
            } else {
                misses++
                if (lastSeen >= 0 && misses > MAX_GAP) break
                if (lastSeen < 0 && misses > LOOK_AHEAD) break
            }
        }
        val start = lastSeen + 1
        return if (start <= next.lastIndex) start else null
    }

    /** The digits of [text] all appear, in order, in [old] (a cut line shows fewer of them; a different number never matches). */
    private fun sameNumbers(text: String, old: String): Boolean =
        old.filter { it.isDigit() }.contains(text.filter { it.isDigit() })
}
