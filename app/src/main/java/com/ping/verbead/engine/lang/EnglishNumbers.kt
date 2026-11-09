package com.ping.verbead.engine.lang

/**
 * Spoken English numbers to digits ("twenty three" -> 23). Conservative, like the Chinese converter:
 *
 * - A number of 10 or more, or anything with hundred/thousand/million..., is converted ("twelve", "one hundred and five",
 *   "two thousand twenty five", "a hundred").
 * - A single digit word (one ... nine, zero) stays a word in running text ("one of them", "I have two cats") and is only
 *   converted in a clearly numeric context: followed by percent or a unit (dollars, kg ...), part of a calculation
 *   ("two plus three equals five"), a decimal ("three point five"), a time ("three p m", "five o'clock"), or a digit string.
 * - Years: "twenty twenty five" -> 2025, "nineteen eighty four" -> 1984.
 * - Digit strings of three or more digit words that are not just counting up or down: "five five five one two three four" -> 5551234.
 * - Operators (plus, minus, times, divided by, equals) become symbols only when numbers stand on both sides.
 * - Ordinals, fractions ("one half") and idioms ("one of", "no one") are left alone.
 */
object EnglishNumbers {

    private val ONES = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9,
    )
    private val TEENS = mapOf(
        "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14, "fifteen" to 15,
        "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19,
    )
    private val TENS = mapOf(
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90,
    )
    private val SCALES = mapOf("thousand" to 1_000L, "million" to 1_000_000L, "billion" to 1_000_000_000L, "trillion" to 1_000_000_000_000L)

    /** Words after which even a single digit is a quantity. */
    private val UNITS = setOf(
        "percent", "dollar", "dollars", "euro", "euros", "cent", "cents", "yen", "pound", "pounds",
        "degree", "degrees", "kilogram", "kilograms", "kilo", "kilos", "gram", "grams", "kilometer", "kilometers",
        "kilometre", "kilometres", "meter", "meters", "metre", "metres", "mile", "miles", "centimeter", "centimeters",
        "megabyte", "megabytes", "gigabyte", "gigabytes", "liter", "liters", "litre", "litres",
    )

    private val OPERATORS = mapOf(
        "plus" to "+", "minus" to "-", "times" to "×", "multiplied by" to "×", "divided by" to "÷",
        "equals" to "=", "equal to" to "=",
    )

    private val TOKEN = Regex("[A-Za-z']+|\\d+(?:\\.\\d+)?|[^A-Za-z'\\d]+")
    private val DIGITS_ONLY = Regex("\\d+(?:\\.\\d+)?")

    private class Phrase(
        val first: Int,
        val last: Int,
        val value: String,
        /** true when the text is a number by itself (>= 10, a scale word, a decimal, a year, a digit string ...) */
        val strong: Boolean,
        val singleDigit: Boolean,
    )

    fun convert(text: String): String {
        if (text.isEmpty()) return text
        val tokens = TOKEN.findAll(text).map { it.value }.toMutableList()
        if (tokens.none { isNumberWord(it.lowercase()) }) return text

        val phrases = findPhrases(tokens)
        if (phrases.isEmpty()) return text

        // which phrases are really numbers here
        val use = BooleanArray(phrases.size)
        val negative = BooleanArray(phrases.size)
        for ((i, p) in phrases.withIndex()) {
            val before = prevWord(tokens, p.first)
            val after = nextWord(tokens, p.last)
            var numeric = p.strong ||
                (after != null && after.lowercase() in UNITS) ||
                (after != null && after.lowercase().let { it == "percent" || it == "o'clock" || it == "am" || it == "pm" }) ||
                isTimeSuffix(tokens, p.last)
            if (!numeric) {
                // inside a calculation: a number word or digits on the other side of an operator
                numeric = operatorBetween(tokens, phrases, i, i - 1) != null || operatorBetween(tokens, phrases, i, i + 1) != null
            }
            use[i] = numeric
            if (before != null && (before.lowercase() == "negative" || before.lowercase() == "minus") && numeric &&
                (i == 0 || !adjacentOperand(tokens, phrases, i))
            ) negative[i] = true
        }

        val replaced = HashMap<Int, Pair<Int, String>>() // first token -> (last token, text)
        for ((i, p) in phrases.withIndex()) {
            if (!use[i]) continue
            var first = p.first
            var value = p.value
            if (negative[i]) {
                val signIdx = prevWordIndex(tokens, p.first)
                if (signIdx >= 0) { first = signIdx; value = "-$value" }
            }
            replaced[first] = p.last to value
        }

        // operators between two numbers
        val opAt = HashMap<Int, Pair<Int, String>>()
        for (i in 0 until phrases.size - 1) {
            val op = operatorBetween(tokens, phrases, i, i + 1) ?: continue
            if (use[i] && use[i + 1]) opAt[op.first] = op.second to op.third
        }
        // a number next to a digit literal ("2 plus 3") also makes the operator count
        val out = StringBuilder(text.length)
        var t = 0
        while (t < tokens.size) {
            val r = replaced[t]
            val o = opAt[t]
            when {
                r != null -> { out.append(r.second); t = r.first + 1 }
                o != null -> { out.append(o.second); t = o.first + 1 }
                else -> { out.append(tokens[t]); t++ }
            }
        }
        return out.toString()
    }

    // ── finding number phrases ───────────────────────────────────────────

    private fun isNumberWord(w: String) = w in ONES || w in TEENS || w in TENS || w in SCALES || w == "hundred" || w == "oh" || w == "point"

    private fun isWord(tok: String) = tok.isNotEmpty() && (tok[0].isLetter() || tok[0] == '\'')

    private fun joiner(tok: String) = tok == " " || tok == "-"

    /** Greedy left-to-right scan for number phrases; returns them in order, non-overlapping. */
    private fun findPhrases(tokens: List<String>): List<Phrase> {
        val result = ArrayList<Phrase>()
        var i = 0
        while (i < tokens.size) {
            val w = tokens[i].lowercase()
            val startsNumber = isWord(tokens[i]) && (w in ONES || w in TEENS || w in TENS || (w == "a" && aScale(tokens, i)) ||
                w == "oh" && false || (w == "point" && decimalAfter(tokens, i + 1) != null))
            if (!startsNumber) { i++; continue }
            val p = readPhrase(tokens, i)
            if (p == null) { i++; continue }
            result += p
            i = p.last + 1
        }
        return mergeYears(tokens, result)
    }

    private fun aScale(tokens: List<String>, i: Int): Boolean {
        if (tokens[i].lowercase() != "a") return false
        val j = i + 2
        return i + 2 < tokens.size && joiner(tokens[i + 1]) && tokens[i + 1] == " " &&
            (tokens[j].lowercase() == "hundred" || tokens[j].lowercase() in SCALES)
    }

    /** Words (lower case) of the digit string starting at [i] ("one four one" ...), as digits and the last token index. */
    private fun decimalAfter(tokens: List<String>, i: Int): Pair<String, Int>? {
        var j = i
        if (j < tokens.size && tokens[j] == " ") j++
        val sb = StringBuilder()
        var last = -1
        while (j < tokens.size) {
            val w = tokens[j].lowercase()
            val d = if (w == "oh") 0 else ONES[w]
            if (d == null || !isWord(tokens[j])) break
            sb.append(d)
            last = j
            j++
            if (j < tokens.size && tokens[j] == " " && j + 1 < tokens.size && (tokens[j + 1].lowercase() in ONES || tokens[j + 1].lowercase() == "oh")) j++ else break
        }
        return if (sb.isEmpty()) null else sb.toString() to last
    }

    private fun readPhrase(tokens: List<String>, start: Int): Phrase? {
        val first = tokens[start].lowercase()
        // ".5" style: "point five"
        if (first == "point") {
            val dec = decimalAfter(tokens, start + 1) ?: return null
            if (start > 0 && joiner(tokens[start - 1]) && start >= 2 && isNumberWord(tokens[start - 2].lowercase())) return null
            return Phrase(start, dec.second, "0." + dec.first, strong = true, singleDigit = false)
        }
        // digit string: three or more digit words that are not just counting
        val digits = readDigitString(tokens, start)
        if (digits != null) return digits

        val card = parseCardinal(tokens, start) ?: return null
        var last = card.last
        var value = card.value.toString()
        var strong = card.strong
        // decimal part
        val afterIdx = last + 1
        if (afterIdx + 1 < tokens.size && tokens[afterIdx] == " " && tokens[afterIdx + 1].lowercase() == "point") {
            val dec = decimalAfter(tokens, afterIdx + 2)
            if (dec != null) {
                value = value + "." + dec.first
                last = dec.second
                strong = true
                // "three point five million"
                val scaleIdx = last + 2
                if (scaleIdx < tokens.size && tokens[last + 1] == " " && tokens[scaleIdx].lowercase() in SCALES) {
                    return Phrase(start, scaleIdx, value + " " + tokens[scaleIdx], strong = true, singleDigit = false)
                }
            }
        }
        // "five percent" / "per cent"
        return Phrase(start, last, value, strong, singleDigit = card.single && !strong)
    }

    private class Card(val value: Long, val last: Int, val strong: Boolean, val single: Boolean)

    private fun onesAt(tokens: List<String>, i: Int): Int? =
        if (i < tokens.size && isWord(tokens[i])) ONES[tokens[i].lowercase()] else null

    private fun parseChunk(tokens: List<String>, start: Int): Pair<Int, Int>? {
        var j = start
        var v = 0
        var consumed = false
        val a = tokens[j].lowercase()
        if (a == "a" && j + 2 < tokens.size && tokens[j + 1] == " " && tokens[j + 2].lowercase() == "hundred") {
            v = 100; j += 3; consumed = true
        } else {
            val o = onesAt(tokens, j)
            if (o != null && o != 0 && j + 2 < tokens.size && joiner(tokens[j + 1]) && tokens[j + 2].lowercase() == "hundred") {
                v = o * 100; j += 3; consumed = true
            }
        }
        if (consumed && j + 2 < tokens.size && tokens[j] == " " && tokens[j + 1].lowercase() == "and" && tokens[j + 2] == " ") {
            val next = tokens.getOrNull(j + 3)?.lowercase()
            if (next != null && (next in ONES || next in TEENS || next in TENS)) j += 3
        }
        val afterHundred = j
        var skippedJoiner = false
        if (consumed && j < tokens.size && joiner(tokens[j]) && j + 1 < tokens.size) {
            // "one hundred twenty three"
            j++
            skippedJoiner = true
        }
        if (j < tokens.size && isWord(tokens[j])) {
            val w = tokens[j].lowercase()
            val teen = TEENS[w]
            val ten = TENS[w]
            val one = ONES[w]
            if (teen != null) { v += teen; j++; consumed = true }
            else if (ten != null) {
                v += ten; j++; consumed = true
                if (j + 1 < tokens.size && joiner(tokens[j])) {
                    val o = onesAt(tokens, j + 1)
                    if (o != null && o != 0) { v += o; j += 2 }
                }
            } else if (one != null && (one != 0 || !consumed)) {
                if (!consumed || one != 0) { v += one; j++; consumed = true }
            }
        }
        if (skippedJoiner && j == afterHundred + 1) j = afterHundred // a joiner was skipped but no number followed
        return if (consumed) v to j else null
    }

    private fun parseCardinal(tokens: List<String>, start: Int): Card? {
        var i = start
        var total = 0L
        var lastScale = Long.MAX_VALUE
        var any = false
        var lastEnd = start - 1
        var usedScale = false
        var firstChunk: Int? = null
        var firstWasSingle = false
        while (i < tokens.size) {
            val chunk = parseChunk(tokens, i) ?: break
            var end = chunk.second - 1
            val after = chunk.second
            if (after + 1 < tokens.size && tokens[after] == " " && tokens[after + 1].lowercase() in SCALES) {
                val scale = SCALES.getValue(tokens[after + 1].lowercase())
                if (scale >= lastScale) break
                total += chunk.first.toLong() * scale
                lastScale = scale
                usedScale = true
                any = true
                end = after + 1
                lastEnd = end
                if (firstChunk == null) { firstChunk = chunk.first; firstWasSingle = false }
                // next chunk may follow after "and"/space
                var n = end + 1
                if (n + 1 < tokens.size && tokens[n] == " ") {
                    n++
                    if (tokens.getOrNull(n)?.lowercase() == "and" && tokens.getOrNull(n + 1) == " ") n += 2
                    i = n
                    continue
                }
                break
            }
            total += chunk.first
            any = true
            lastEnd = end
            if (firstChunk == null) { firstChunk = chunk.first; firstWasSingle = chunk.first in 0..9 }
            break
        }
        if (!any) return null
        val strong = usedScale || total >= 10 || (tokens[start].lowercase() == "a")
        val single = !usedScale && total in 0..9 && firstWasSingle
        // idioms: "one of", "one another"
        return Card(total, lastEnd, strong, single)
    }

    private fun readDigitString(tokens: List<String>, start: Int): Phrase? {
        var j = start
        val sb = StringBuilder()
        var count = 0
        var last = start
        val seq = ArrayList<Int>()
        while (j < tokens.size && isWord(tokens[j])) {
            val w = tokens[j].lowercase()
            val d = if (w == "oh" && count > 0) 0 else ONES[w]
            if (d == null) break
            sb.append(d); seq += d; count++
            last = j
            if (j + 2 < tokens.size && tokens[j + 1] == " " && (tokens[j + 2].lowercase() in ONES || tokens[j + 2].lowercase() == "oh")) j += 2 else break
        }
        if (count < 3) return null
        val monotone = seq.zipWithNext().all { (a, b) -> b == a + 1 } || seq.zipWithNext().all { (a, b) -> b == a - 1 }
        if (monotone) return null
        return Phrase(start, last, sb.toString(), strong = true, singleDigit = false)
    }

    /** "twenty twenty five", "nineteen eighty four": two adjacent two-digit numbers forming a year. */
    private fun mergeYears(tokens: List<String>, phrases: List<Phrase>): List<Phrase> {
        val out = ArrayList<Phrase>()
        var i = 0
        while (i < phrases.size) {
            val a = phrases[i]
            val b = phrases.getOrNull(i + 1)
            if (b != null && a.last + 2 == b.first && tokens[a.last + 1] == " " &&
                a.value.toIntOrNull() in 19..20 && b.value.length == 2 && b.value.toIntOrNull() != null &&
                tokens[a.first].lowercase().let { it == "nineteen" || it == "twenty" }
            ) {
                out += Phrase(a.first, b.last, a.value + b.value, strong = true, singleDigit = false)
                i += 2
                continue
            }
            out += a
            i++
        }
        return out
    }

    // ── context helpers ──────────────────────────────────────────────────

    private fun prevWordIndex(tokens: List<String>, idx: Int): Int {
        var j = idx - 1
        if (j >= 0 && tokens[j] == " ") j--
        return if (j >= 0 && isWord(tokens[j])) j else -1
    }

    private fun prevWord(tokens: List<String>, idx: Int): String? = prevWordIndex(tokens, idx).let { if (it >= 0) tokens[it] else null }

    private fun nextWord(tokens: List<String>, idx: Int): String? {
        var j = idx + 1
        if (j < tokens.size && tokens[j] == " ") j++
        return if (j < tokens.size && isWord(tokens[j])) tokens[j] else null
    }

    /** "3 pm", "three p m": the token after the number spells a.m./p.m. */
    private fun isTimeSuffix(tokens: List<String>, last: Int): Boolean {
        var j = last + 1
        if (j < tokens.size && tokens[j] == " ") j++
        val a = tokens.getOrNull(j)?.lowercase() ?: return false
        if (a == "am" || a == "pm") return true
        if ((a == "a" || a == "p") && tokens.getOrNull(j + 1) == " " && tokens.getOrNull(j + 2)?.lowercase() == "m") return true
        return false
    }

    /**
     * The operator spelled between phrase [i] and phrase [j] (neighbours), as (first token index, last token index, symbol),
     * or null. Digit literals count as numbers on the far side.
     */
    private fun operatorBetween(tokens: List<String>, phrases: List<Phrase>, i: Int, j: Int): Triple<Int, Int, String>? {
        if (j < 0 || j >= phrases.size) {
            return null
        }
        val left = if (i < j) phrases[i] else phrases[j]
        val right = if (i < j) phrases[j] else phrases[i]
        return operatorTokens(tokens, left.last, right.first)
    }

    private fun operatorTokens(tokens: List<String>, afterIdx: Int, beforeIdx: Int): Triple<Int, Int, String>? {
        // tokens between: " " word [" " word] " "
        val between = ((afterIdx + 1) until beforeIdx).map { tokens[it] }
        if (between.size < 3 || between.first() != " " || between.last() != " ") return null
        val words = between.subList(1, between.size - 1).joinToString("").lowercase()
        val sym = OPERATORS[words] ?: return null
        return Triple(afterIdx + 2, beforeIdx - 2, sym)
    }

    private fun adjacentOperand(tokens: List<String>, phrases: List<Phrase>, i: Int): Boolean {
        val prevIdx = prevWordIndex(tokens, phrases[i].first)
        if (prevIdx <= 0) return false
        // number directly before the sign word ("five minus three")
        val before = prevWordIndex(tokens, prevIdx)
        return before >= 0 && isNumberWord(tokens[before].lowercase())
    }
}
