package com.ping.verbead.tts

/**
 * Removes what a speech engine reads badly or not at all: URLs, emoji and pictographs, zero-width
 * and control characters. Line breaks are kept (they are sentence boundaries) but runs of blanks
 * and empty lines are collapsed.
 */
object TtsTextCleaner {

    private val URL = Regex("""(?i)\b(?:https?://|www\.)\S+""")
    private val INVISIBLE = Regex("[\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u2064\\uFEFF\\u00AD]")
    private val CONTROL = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]")
    /**
     * Citation marks such as [1], [note 2], [a] and the "[edit]" of a section: written for the eye, read as noise.
     * The Chinese words are written as escapes (note: U+8A3B or U+6CE8; edit: U+7DE8 U+8F2F or U+7F16 U+8F91) so no
     * Chinese text is hard-coded in the source.
     */
    private val CITATION = Regex(
        """\[\s*(?:(?:\u8A3B|\u6CE8|note|n|fn)\s*)?\d{1,3}\s*\]""" +
            """|\[\s*[a-z]\s*\]""" +
            """|\[\s*(?:\u7DE8\u8F2F|\u7F16\u8F91|edit)\s*\]""",
        RegexOption.IGNORE_CASE
    )
    private val BLANKS = Regex("[ \\t\\u00A0\\u3000]+")
    private val EMPTY_LINES = Regex("\\n(?:[ \\t]*\\n)+")

    fun clean(text: String): String {
        var s = text.replace("\r\n", "\n").replace('\r', '\n')
        s = URL.replace(s, " ")
        s = CITATION.replace(s, "")
        s = INVISIBLE.replace(s, "")
        s = CONTROL.replace(s, "")
        s = stripPictographs(s)
        s = BLANKS.replace(s, " ")
        s = s.lines().joinToString("\n") { it.trim() }
        s = EMPTY_LINES.replace(s, "\n")
        return s.trim()
    }

    private fun stripPictographs(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            if (!isPictograph(cp)) sb.appendCodePoint(cp)
        }
        return sb.toString()
    }

    private fun isPictograph(cp: Int): Boolean = when {
        cp in 0x1F000..0x1FAFF -> true            // emoji, symbols, flags, extended pictographs
        cp in 0x2600..0x27BF -> true               // misc symbols and dingbats
        cp in 0x2B00..0x2BFF -> true               // arrows and stars used as emoji
        cp in 0xFE00..0xFE0F -> true               // variation selectors
        cp == 0x200D -> true                       // zero width joiner
        cp in 0xE0020..0xE007F -> true             // tag characters (subdivision flags)
        else -> false
    }
}
