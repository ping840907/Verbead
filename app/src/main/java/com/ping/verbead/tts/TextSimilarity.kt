package com.ping.verbead.tts

/** How much two pieces of text agree, tolerating the few wrong characters text recognition makes. */
object TextSimilarity {

    /** Letters and digits only, lower case: punctuation, spaces and line breaks do not count. */
    fun normalize(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            if (Character.isLetterOrDigit(cp)) sb.appendCodePoint(Character.toLowerCase(cp))
        }
        return sb.toString()
    }

    /** The number of letters and digits in [s]. */
    fun letterCount(s: String): Int = normalize(s).let { it.codePointCount(0, it.length) }

    /**
     * The share (0..1) of [part]'s neighbouring character pairs that also occur in [whole]. A line read from a
     * picture is a piece of the node text, so containment (not equality) is what matters; and one wrong
     * character only spoils two pairs, so a line with a typo still scores high. A single character is
     * compared on its own.
     */
    fun containment(part: String, whole: String): Float {
        val a = normalize(part)
        val b = normalize(whole)
        if (a.isEmpty() || b.isEmpty()) return 0f
        if (a.length == 1) return if (b.contains(a)) 1f else 0f
        val pairs = HashSet<String>(b.length)
        for (i in 0 until b.length - 1) pairs += b.substring(i, i + 2)
        var hit = 0
        for (i in 0 until a.length - 1) if (a.substring(i, i + 2) in pairs) hit++
        return hit.toFloat() / (a.length - 1)
    }
}
