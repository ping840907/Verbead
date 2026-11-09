package com.ping.verbead.engine.lang

import com.ping.verbead.engine.SpokenNumberConverter

/** 簡轉繁的強度。 */
enum class ScriptMode {
    /** 不轉換，原樣保留辨識結果 */
    OFF,

    /** 只做台灣用詞與異體字修正（X-ASR 原生輸出繁體，不需要再簡轉繁） */
    VARIANTS_ONLY,

    /** 完整簡轉繁加台灣用詞 */
    FULL,
}

/** 全形與中日韓常用標點。 */
object CjkPunctuation {
    private val REGEX = Regex("[，。！？、…：；「」『』—～（）《》〈〉【】〔〕]")

    fun filter(text: String): String = text.replace(REGEX, "")
}

/** Half-width (ASCII) punctuation of the kinds that end up in dictated text. */
object AsciiPunctuation {
    private const val ALWAYS = "!?;\"()[]{}"
    private const val UNLESS_BETWEEN_DIGITS = ".,:"

    /**
     * Removes half-width punctuation. A point, comma or colon between two digits stays (3.14, 1,000, 10:30);
     * apostrophes and hyphens stay (don't, well-known). Spaces left double by a removal are folded.
     */
    fun filter(text: String): String {
        val sb = StringBuilder(text.length)
        var removed = false
        for (i in text.indices) {
            val c = text[i]
            val drop = when (c) {
                in ALWAYS -> true
                in UNLESS_BETWEEN_DIGITS -> !(i > 0 && text[i - 1].isDigit() && i + 1 < text.length && text[i + 1].isDigit())
                else -> false
            }
            if (drop) removed = true else sb.append(c)
        }
        if (!removed) return text
        return sb.toString().replace(Regex(" {2,}"), " ").trimEnd()
    }
}

/**
 * 繁體中文（台灣）的辨識後處理：簡轉繁、台灣用詞、口語數字、標點過濾。
 * 規則以字元證據逐句判斷，日韓文句子不會被動到，所以辨識結果夾雜其他語言也安全。
 */
object ZhTwProfile {
    fun convertScript(raw: String, mode: ScriptMode): String = when (mode) {
        ScriptMode.OFF -> raw
        ScriptMode.VARIANTS_ONLY -> ZhTwRules.normalizeTaiwanVariants(raw)
        ScriptMode.FULL -> ZhTwRules.toTaiwanTraditional(raw)
    }

    /** 使用者詞彙置換之後的詞彙與異體字修正 */
    fun applyVariantRules(text: String): String = ZhTwRules.applyPostRegexReplacements(text)

    fun convertSpokenNumbers(text: String): String = SpokenNumbers.convert(text)

    fun filterPunctuation(text: String): String = CjkPunctuation.filter(text)

    fun filterHalfWidthPunctuation(text: String): String = AsciiPunctuation.filter(text)
}
