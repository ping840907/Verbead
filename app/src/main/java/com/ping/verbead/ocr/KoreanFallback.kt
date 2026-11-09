package com.ping.verbead.ocr

/**
 * Cheap rules for when the (optional) Korean recognizer is worth running.
 *
 * The PP-OCRv6 recognizers have no Hangul in their dictionary, so on Korean text they drop the syllables or invent
 * look-alike ideographs. Running a second model on every strip would double the work, so it only runs when the first
 * result looks like that (see [primaryLooksUnsupported]; measured on rendered Chinese/English/Japanese strips, which
 * never trip it, versus Korean strips, which almost always do). Once Korean has been adopted for a strip, the following
 * strips of the same page try Korean first ([trustKoreanFirst]) so a Korean page costs about one model per strip.
 */
object KoreanFallback {
    /** Below this mean confidence the first result is not trusted. */
    const val CONF_TRIGGER = 0.90f

    /**
     * A silent stretch this long (in 8 px time steps, about 1.5 characters per 9 steps) between two decoded characters
     * means something was dropped. Measured on real detector crops: genuine Chinese/English/Japanese lines reach 11
     * (wide word gaps), Korean strips the Chinese model could not read reach 17 to 54.
     */
    const val INNER_GAP_TRIGGER_STEPS = 12

    /** Same for the stretch before the first / after the last decoded character; it includes the crop's own margin (up to 13 on genuine lines). */
    const val EDGE_GAP_TRIGGER_STEPS = 16

    /** The Korean result must be at least this confident to be adopted. */
    const val ADOPT_CONF = 0.80f

    /** When trying Korean first, a result this confident is final (the first model is skipped). */
    const val FIRST_CONF = 0.90f

    /** Stray non-Hangul characters at the edge of a Korean result below this probability are dropped (see [CtcDecoder.decode]). */
    const val EDGE_MIN_PROB = 0.6f

    fun isHangul(c: Char): Boolean {
        val code = c.code
        return code in 0xAC00..0xD7A3 || code in 0x1100..0x11FF || code in 0x3130..0x318F
    }

    fun containsHangul(s: String): Boolean = s.any { isHangul(it) }

    fun primaryLooksUnsupported(primary: RecResult): Boolean =
        primary.emitted == 0 || primary.conf < CONF_TRIGGER ||
            primary.innerGapSteps >= INNER_GAP_TRIGGER_STEPS ||
            maxOf(primary.leadGapSteps, primary.trailGapSteps) >= EDGE_GAP_TRIGGER_STEPS

    private fun validCount(s: String): Int = s.count { it.isLetterOrDigit() }

    /** Adopt the Korean result only if it really contains Hangul and explains the strip at least as well as the first one. */
    fun shouldAdoptKorean(primary: RecResult, korean: RecResult): Boolean =
        containsHangul(korean.text) &&
            korean.conf >= ADOPT_CONF &&
            validCount(korean.text) >= validCount(primary.text)

    fun trustKoreanFirst(korean: RecResult): Boolean =
        containsHangul(korean.text) && korean.conf >= FIRST_CONF
}
