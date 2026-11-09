package com.ping.verbead.engine.lang

/**
 * Kanji numerals in Japanese text to digits ("二千二十五年" -> "2025年"). Conservative:
 *
 * - A run with a magnitude (十 百 千 万 億 兆) is converted ("三百", "二十三", "一万円"); a lone magnitude only before a unit
 *   ("百円"), never in words such as "十分" or "万が一".
 * - A run of two or more plain digits is a digit string ("二〇二五" -> "2025", "一二三" -> "123").
 * - A single digit is converted only before a date/money/percent unit ("三月", "五円"), and never in "一時" "一番" "一緒"-like words.
 * - "三点一四" -> "3.14"; "五パーセント" -> "5パーセント" is left to the digit.
 */
object JapaneseNumbers {
    private const val DIGITS = "〇一二三四五六七八九"
    private const val MAGNITUDES = "十百千万億兆"
    private val RUN = Regex("[$DIGITS$MAGNITUDES]+(?:点[$DIGITS]+)?")

    /** Units that make even one digit a quantity. */
    private val UNIT_AFTER = Regex("^(?:年|月|円|歳|%|％|パーセント|キロ|メートル|センチ|ミリ|グラム|度|秒|時間)")

    /** Words that contain a numeral but are not numbers (checked on the text around the run). */
    private val IDIOM = Regex("(?:十分|万一|万が一|一時|一番|一緒|一般|一度|一人|二人|百貨|百科|千代|万能|万歳|八百屋|七五三|十字|四季|三昧)")

    fun convert(text: String): String {
        if (text.none { it in DIGITS || it in MAGNITUDES }) return text
        val out = StringBuilder(text.length)
        var last = 0
        for (m in RUN.findAll(text)) {
            val run = m.value
            val rest = text.substring(m.range.last + 1)
            val value = convertRun(run, rest, text, m.range.first) ?: continue
            out.append(text, last, m.range.first).append(value)
            last = m.range.last + 1
        }
        out.append(text, last, text.length)
        return out.toString()
    }

    private fun convertRun(run: String, rest: String, text: String, start: Int): String? {
        // inside an idiom? look at the words around the run
        val from = (start - 2).coerceAtLeast(0)
        val to = (start + run.length + 2).coerceAtMost(text.length)
        if (IDIOM.containsMatchIn(text.substring(from, to)) && IDIOM.findAll(text.substring(from, to)).any {
                val s = from + it.range.first
                val e = from + it.range.last + 1
                s <= start && e >= start + run.length || s < start + run.length && e > start
            }
        ) return null

        val pointAt = run.indexOf('点')
        if (pointAt >= 0) {
            val intPart = run.substring(0, pointAt)
            val frac = run.substring(pointAt + 1)
            val iv = if (intPart.isEmpty()) return null else parseInt(intPart) ?: return null
            return iv.toString() + "." + frac.map { DIGITS.indexOf(it) }.joinToString("")
        }
        val hasMagnitude = run.any { it in MAGNITUDES }
        val hasUnit = UNIT_AFTER.containsMatchIn(rest)
        return when {
            hasMagnitude && run.length == 1 -> if (hasUnit && !rest.startsWith("時間")) parseInt(run)?.toString() else null
            hasMagnitude -> parseInt(run)?.toString()
            run.length >= 2 -> run.map { DIGITS.indexOf(it) }.joinToString("")
            else -> if (hasUnit && !rest.startsWith("時間")) DIGITS.indexOf(run[0]).toString() else null
        }
    }

    /** 二千二十五 -> 2025, 三億五千万 -> 350000000. Null when the run is not well formed. */
    private fun parseInt(s: String): Long? {
        var total = 0L
        var section = 0L // value below the next 万/億/兆
        var digit = -1L
        for (ch in s) {
            when (ch) {
                in DIGITS -> digit = if (digit < 0) DIGITS.indexOf(ch).toLong() else return null
                '十', '百', '千' -> {
                    val unit = when (ch) { '十' -> 10L; '百' -> 100L; else -> 1000L }
                    section += (if (digit < 0) 1L else digit) * unit
                    digit = -1
                }
                else -> {
                    val big = when (ch) { '万' -> 10_000L; '億' -> 100_000_000L; else -> 1_000_000_000_000L }
                    val v = section + (if (digit < 0) 0L else digit)
                    if (v == 0L) return null
                    total += v * big
                    section = 0
                    digit = -1
                }
            }
        }
        return total + section + (if (digit < 0) 0L else digit)
    }
}
