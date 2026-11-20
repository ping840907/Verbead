package com.ping.verbead.tts

/** Which language a piece of text should be spoken in; decided from the writing system. */
enum class SpeechLang { ZH, JA, KO, OTHER }

object LanguageGuess {

    /**
     * Kana means Japanese, Hangul means Korean, and Han characters alone mean Chinese. Mixed text
     * goes to the language of the dominant script, so an English sentence with one Chinese word is
     * still read as English.
     */
    fun of(text: String): SpeechLang {
        var han = 0
        var kana = 0
        var hangul = 0
        var latin = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            when {
                cp in 0x3040..0x30FF || cp in 0x31F0..0x31FF -> kana++
                cp in 0xAC00..0xD7AF || cp in 0x1100..0x11FF || cp in 0x3130..0x318F -> hangul++
                cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF || cp in 0xF900..0xFAFF || cp in 0x20000..0x2A6DF -> han++
                cp in 0x41..0x5A || cp in 0x61..0x7A || cp in 0xC0..0x24F -> latin++
            }
        }
        val cjk = han + kana + hangul
        if (cjk == 0) return SpeechLang.OTHER
        if (latin > cjk * 2) return SpeechLang.OTHER
        return when {
            kana > 0 && kana * 4 >= han -> SpeechLang.JA
            hangul > han -> SpeechLang.KO
            else -> SpeechLang.ZH
        }
    }
}
