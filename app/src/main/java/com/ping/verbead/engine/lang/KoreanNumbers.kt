package com.ping.verbead.engine.lang

/**
 * Spoken Korean numbers to digits. Conservative, because many one-syllable numerals are everyday words (이 this, 오 come, 사 buy):
 *
 * - Sino-Korean runs with a magnitude (십 백 천 만 억 조) become digits at a word boundary ("이십삼" -> 23, "삼백" -> 300);
 *   a lone magnitude only before a unit ("만 원" -> 10000원).
 * - A run followed by a unit ("삼 층", "이천이십오 년", "오십 퍼센트") is converted; single syllables 이 일 오 사 구 are never
 *   converted alone, since they are usually words or particles.
 * - Native Korean numbers before a counter ("스물한 살" -> 21살, "세 개" -> 3개, "열두 시" -> 12시).
 * - Decimals: "영 점 오" -> 0.5, "삼 점 일 사" -> 3.14.
 */
object KoreanNumbers {
    private const val SINO_DIGITS = "영공일이삼사오육칠팔구"
    private const val SINO_MAG = "십백천만억조"
    private const val SINO = SINO_DIGITS + SINO_MAG
    private const val HANGUL = "가-힣"

    private val UNITS = "(?:퍼센트|프로|킬로그램|킬로미터|킬로|미터|센티|밀리|그램|도|년|월|원|층|호|번|개월|개|명|살|시간|시|분|초|점|대|장|권|마리|잔|병|곳|주|회|등|배|%)"
    private val NATIVE_COUNTER = "(?:시간|시|개월|개|명|살|마리|번|권|대|잔|병|장|송이|곳|달|분|켤레|채|그루|가지|자루|사람)"

    private val SINO_RUN = Regex("(?<![$HANGUL])([$SINO]+)( ?)(?=$UNITS(?![$HANGUL])|$UNITS(?=[가이은는를을에의도만씩째로와과쯤]))")
    private val SINO_PLAIN = Regex("(?<![$HANGUL])([$SINO]{2,})(?![$HANGUL])")
    private val DECIMAL = Regex("(?<![$HANGUL])([영공삼육칠팔구$SINO_MAG]+|[$SINO]*[$SINO_MAG][$SINO]*) ?점 ?([$SINO_DIGITS]+)(?![$HANGUL])")

    private val NATIVE_TENS = mapOf("열" to 10, "스물" to 20, "스무" to 20, "서른" to 30, "마흔" to 40, "쉰" to 50, "예순" to 60, "일흔" to 70, "여든" to 80, "아흔" to 90)
    private val NATIVE_ONES = mapOf(
        "하나" to 1, "한" to 1, "둘" to 2, "두" to 2, "셋" to 3, "세" to 3, "석" to 3, "넷" to 4, "네" to 4, "넉" to 4,
        "다섯" to 5, "여섯" to 6, "일곱" to 7, "여덟" to 8, "아홉" to 9,
    )
    private val NATIVE = Regex(
        "(?<![$HANGUL])(열|스물|스무|서른|마흔|쉰|예순|일흔|여든|아흔)?(하나|한|둘|두|셋|세|넷|네|다섯|여섯|일곱|여덟|아홉)? ?" +
            "(?=$NATIVE_COUNTER(?![$HANGUL])|$NATIVE_COUNTER(?=[가이은는를을에의도만씩째로와과쯤]))"
    )

    fun convert(text: String): String {
        if (text.none { it in '가'..'힣' }) return text
        var t = text
        t = DECIMAL.replace(t) { m ->
            val iv = parse(m.groupValues[1]) ?: return@replace m.value
            iv.toString() + "." + m.groupValues[2].map { SINO_DIGITS.indexOf(it).let { i -> if (i == 0 || i == 1) 0 else i - 1 } }.joinToString("")
        }
        t = SINO_RUN.replace(t) { m ->
            val run = m.groupValues[1]
            val hasMag = run.any { it in SINO_MAG }
            when {
                run.length == 1 && run[0] in "이일오사구" -> m.value
                run.length == 1 && run[0] in SINO_MAG && run[0] != '만' && run[0] != '천' && run[0] != '백' -> m.value
                run.length == 1 && !hasMag && run[0] in "영공" -> m.value
                else -> (parse(run)?.toString() ?: run) + ""
            }
        }
        t = SINO_PLAIN.replace(t) { m ->
            val run = m.value
            if (run.none { it in SINO_MAG }) m.value else parse(run)?.toString() ?: m.value
        }
        t = NATIVE.replace(t) { m ->
            val tens = m.groupValues[1]
            val ones = m.groupValues[2]
            if (tens.isEmpty() && ones.isEmpty()) return@replace m.value
            val v = (NATIVE_TENS[tens] ?: 0) + (NATIVE_ONES[ones] ?: 0)
            if (v == 0) m.value else v.toString()
        }
        return t
    }

    /** 이천이십오 -> 2025, 삼백 -> 300. Digit-only runs ("이영") are read digit by digit. */
    private fun parse(s: String): Long? {
        if (s.none { it in SINO_MAG }) return s.map { c -> SINO_DIGITS.indexOf(c).let { if (it < 0) return null else if (it <= 1) 0 else it - 1 } }.joinToString("").toLongOrNull()
        var total = 0L
        var section = 0L
        var digit = -1L
        for (ch in s) {
            val d = SINO_DIGITS.indexOf(ch)
            when {
                d >= 0 -> digit = if (digit < 0) (if (d <= 1) 0L else (d - 1).toLong()) else return null
                ch == '십' || ch == '백' || ch == '천' -> {
                    val unit = when (ch) { '십' -> 10L; '백' -> 100L; else -> 1000L }
                    section += (if (digit < 0) 1L else digit) * unit
                    digit = -1
                }
                else -> {
                    val big = when (ch) { '만' -> 10_000L; '억' -> 100_000_000L; else -> 1_000_000_000_000L }
                    val v = section + (if (digit < 0) 0L else digit)
                    total += (if (v == 0L) 1L else v) * big
                    section = 0
                    digit = -1
                }
            }
        }
        return total + section + (if (digit < 0) 0L else digit)
    }
}
