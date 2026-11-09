package com.ping.verbead.engine.lang

import com.ping.verbead.engine.SpokenNumberConverter

/**
 * Picks the spoken-number converter for each clause by its script:
 * Hangul -> Korean, kana -> Japanese, other CJK ideographs -> Chinese, plain Latin letters -> English.
 */
object SpokenNumbers {
    private val CLAUSE_BOUNDARY = Regex("(?<=[，。！？、；：,.!?;:\\n])")

    fun convert(text: String): String {
        if (text.isEmpty()) return text
        return text.split(CLAUSE_BOUNDARY).joinToString("") { clause ->
            when {
                clause.any { it in '가'..'힣' } -> KoreanNumbers.convert(clause)
                clause.any { it in '぀'..'ヿ' } -> JapaneseNumbers.convert(clause)
                clause.any { it in '一'..'鿿' } -> SpokenNumberConverter.convert(clause)
                clause.any { it in 'A'..'Z' || it in 'a'..'z' } -> EnglishNumbers.convert(clause)
                else -> clause
            }
        }
    }
}
