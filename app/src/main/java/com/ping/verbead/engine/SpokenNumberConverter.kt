package com.ping.verbead.engine

/**
 * 把 ASR 輸出的中文數字與口語化運算符號轉成阿拉伯數字（逆向文字正規化）。
 *
 * 涵蓋：整數（一百二十三、三千五、兩萬三千）、數字串（二零二五）、加減乘除與等於、
 * 百分比（百分之五十、五十趴）、分數（三分之一）、負數、根號，以及「點」的小數／時間判斷。
 * 同時支援繁體與簡體寫法，所以可放在簡轉繁之前或之後。
 *
 * 轉換偏保守，避免誤傷成語與一般用詞，目標是商業場景需要的「純數字」：
 *  - 單獨的一位數（一、二、十…）只在前面有「第」，或處於運算式、分數、百分比等明確的數字語境時
 *    才轉；後面接量詞不算（三個、十分鐘、一天維持原樣），所以「一下、一起、十分好、統一」也不會被動。
 *  - 數字串若是連續遞增或遞減（三四個、一二三）視為概數或數數，不轉。
 *  - 成語與固定用法（三心二意、一天到晚…）整段跳過。
 *  - 含假名或諺文的小句整句跳過。
 *
 * 「點」與「時」的規則（H 為整數，M 為後面的數字；「時」是書面的「點」，如三時十五分）：
 *  - H點半、H點整、H點鐘、H點一刻／三刻 → 時間（3:30、3:00、3:15、3:45）。
 *  - M 後面接「分」，或 M 含十（十五、二十、三十五）→ 時間，因為小數是逐位唸的。
 *  - H 前面有上午、下午、晚上…等時段詞，且 M 是逐位數字 → 時間（下午三點五 = 3:05）。
 *  - 其餘 M 是逐位數字（五、一四一五九）→ 小數（3.5、3.14159）。「時」不會是小數點，所以只做時間判斷。
 *  - 點後面不是數字（一點點、一點意見、三點到）→ 不動。「一時衝動、一時半刻」同理。
 *  - 只有「H點」（含時段詞，如下午三點）→ 不動，因為只有單獨的一位數。
 */
object SpokenNumberConverter {

    fun convert(text: String): String {
        if (text.isEmpty()) return text
        return text.split(CLAUSE_BOUNDARY).joinToString("") { clause ->
            if (clause.isEmpty() || ModelConfig.isJapaneseOrKorean(clause)) clause else convertClause(clause)
        }
    }

    // ── 常數 ──────────────────────────────────────────────────────────────

    private val CLAUSE_BOUNDARY = Regex("(?<=[，。！？、；：,.!?;:\\n])")

    private const val DIGIT_CHARS = "零〇一二三四五六七八九"
    private const val TWO_CHARS = "兩两"
    private const val UNIT_CHARS = "十百千萬万億亿"
    private const val BIG_UNITS = "百千萬万億亿"

    private val TIME_CUES = listOf(
        "上午", "下午", "早上", "晚上", "凌晨", "中午", "傍晚", "半夜", "深夜", "今晚", "明早",
        "早晨", "清晨", "夜裡", "夜里", "晚間", "晚间", "午後", "午后", "現在", "现在",
    )

    /** 成語與固定用法：整段原樣保留。 */
    private val EXCLUDE = Regex(
        listOf(
            "一天到晚", "一年到頭", "一年到头", "一個勁", "一个劲", "一次性", "一本正經", "一本正经",
            "一頭霧水", "一头雾水", "一針見血", "一针见血", "一目了然", "一路平安", "一成不變", "一成不变",
            "一日三餐", "三十而立", "四十不惑", "五十知天命", "三三兩兩", "三三两两", "三五成群",
            "三心二意", "一心一意", "七上八下", "九死一生", "一石二鳥", "一石二鸟", "十全十美",
            "百發百中", "百发百中", "千方百計", "千方百计", "萬無一失", "万无一失", "一模一樣", "一模一样", "一時半刻", "一时半刻", "一時半會", "一时半会",
            "五花八門", "五花八门", "四面八方", "七嘴八舌", "一五一十", "一清二楚", "一窮二白", "一穷二白",
            "五顏六色", "五颜六色", "三言兩語", "三言两语", "三番兩次", "三番两次", "說一不二", "说一不二",
            "獨一無二", "独一无二", "九牛一毛", "接二連三", "接二连三", "一刀兩斷", "一刀两断",
        ).joinToString("|")
    )

    // ── 資料結構 ──────────────────────────────────────────────────────────

    private enum class Kind { SINGLE, DIGITS, POSITIONAL, INVALID }

    /** 一段連續的中文數字字元。 */
    private class Run(
        val start: Int,
        val end: Int,
        val text: String,
        val kind: Kind,
        /** 阿拉伯數字寫法；INVALID 時為空。 */
        val value: String,
        /** 數值；數字串過長或 INVALID 時為 -1。 */
        val number: Long,
    )

    private sealed class Tok {
        /** 一個數字（含小數、時間、百分比…）；never 表示無法確定，連同原字一起保留。 */
        class Num(
            val start: Int,
            val end: Int,
            val value: String,
            val orig: String,
            val strong: Boolean,
            val never: Boolean,
        ) : Tok()

        class Op(val word: String, val symbol: String) : Tok()
        class Raw(val text: String) : Tok()
    }

    // ── 小句轉換 ──────────────────────────────────────────────────────────

    private fun convertClause(s: String): String {
        val excluded = EXCLUDE.findAll(s).associate { it.range.first to it.range.last + 1 }
        val tokens = ArrayList<Tok>()
        var i = 0
        while (i < s.length) {
            val skipTo = excluded[i]
            if (skipTo != null) {
                tokens += Tok.Raw(s.substring(i, skipTo))
                i = skipTo
                continue
            }
            val num = matchNumber(s, i)
            if (num != null) {
                tokens += num
                i = num.end
                continue
            }
            val op = if (tokens.lastOrNull() is Tok.Num) matchOp(s, i) else null
            if (op != null) {
                tokens += op
                i += op.word.length
                continue
            }
            tokens += Tok.Raw(s[i].toString())
            i++
        }

        // 運算符號必須左右都是數字才成立；否則（加入、除了…）還原成原字
        val validOp = BooleanArray(tokens.size)
        for (j in tokens.indices) {
            if (tokens[j] !is Tok.Op) continue
            val left = tokens.getOrNull(j - 1) as? Tok.Num
            val right = tokens.getOrNull(j + 1) as? Tok.Num
            validOp[j] = left != null && right != null && !left.never && !right.never
        }

        val out = StringBuilder(s.length)
        for (j in tokens.indices) {
            when (val t = tokens[j]) {
                is Tok.Raw -> out.append(t.text)
                is Tok.Op -> out.append(if (validOp[j]) t.symbol else t.word)
                is Tok.Num -> {
                    val inChain = (j > 0 && validOp[j - 1]) || (j < tokens.size - 1 && validOp[j + 1])
                    val convert = !t.never && (t.strong || inChain || weakContext(s, t))
                    out.append(if (convert) t.value else t.orig)
                }
            }
        }
        return out.toString()
    }

    private fun matchOp(s: String, i: Int): Tok.Op? = when {
        s.startsWith("乘以", i) -> Tok.Op("乘以", "×")
        s.startsWith("除以", i) -> Tok.Op("除以", "÷")
        s.startsWith("等於", i) -> Tok.Op("等於", "=")
        s.startsWith("等于", i) -> Tok.Op("等于", "=")
        s.startsWith("加", i) -> Tok.Op("加", "+")
        s.startsWith("減", i) -> Tok.Op("減", "-")
        s.startsWith("减", i) -> Tok.Op("减", "-")
        s.startsWith("乘", i) -> Tok.Op("乘", "×")
        s.startsWith("除", i) -> Tok.Op("除", "÷")
        else -> null
    }

    /** 單獨的一位數是否處於明確的數字語境（序數「第」、根號係數）。量詞不算。 */
    private fun weakContext(s: String, num: Tok.Num): Boolean {
        if (num.start > 0 && s[num.start - 1] == '第') return true
        val after = s.substring(num.end)
        return after.startsWith("根號") || after.startsWith("根号")
    }

    private fun hasTimeCue(s: String, start: Int): Boolean {
        val before = s.substring(0, start)
        return TIME_CUES.any { before.endsWith(it) }
    }

    // ── 數字比對 ──────────────────────────────────────────────────────────

    private fun matchNumber(s: String, i: Int): Tok.Num? {
        val c = s[i]
        if (c == '負' || c == '负') {
            val inner = matchCore(s, i + 1)
            if (inner != null && !inner.never && ':' !in inner.value) {
                return Tok.Num(i, inner.end, "-" + inner.value, s.substring(i, inner.end), strong = true, never = false)
            }
        }
        if (s.startsWith("根號", i) || s.startsWith("根号", i)) {
            val inner = matchCore(s, i + 2)
            if (inner != null && !inner.never && ':' !in inner.value) {
                return Tok.Num(i, inner.end, "√" + inner.value, s.substring(i, inner.end), strong = true, never = false)
            }
        }
        for ((prefix, mark) in listOf("百分之" to "%", "千分之" to "‰")) {
            if (!s.startsWith(prefix, i)) continue
            val inner = matchCore(s, i + prefix.length)
            if (inner != null && !inner.never && ':' !in inner.value && '/' !in inner.value) {
                return Tok.Num(i, inner.end, inner.value + mark, s.substring(i, inner.end), strong = true, never = false)
            }
        }
        return matchCore(s, i)
    }

    private fun matchCore(s: String, i: Int): Tok.Num? {
        val run = readRun(s, i) ?: return null
        val idx = run.end
        val orig = s.substring(i, idx)
        if (run.kind == Kind.INVALID) return Tok.Num(i, idx, "", orig, strong = false, never = true)

        // 分數：A分之B → B/A
        if (s.startsWith("分之", idx)) {
            val numerator = readRun(s, idx + 2)
            if (numerator != null && numerator.kind != Kind.INVALID) {
                return Tok.Num(i, numerator.end, "${numerator.value}/${run.value}", s.substring(i, numerator.end), strong = true, never = false)
            }
        }
        // 點：小數或時間
        if (idx < s.length && s[idx] in "點点時时") {
            matchPoint(s, run, idx, hourChar = s[idx] == '時' || s[idx] == '时')?.let { return it }
        }
        // 趴 = %
        if (s.startsWith("趴", idx)) {
            return Tok.Num(i, idx + 1, run.value + "%", s.substring(i, idx + 1), strong = true, never = false)
        }
        return when (run.kind) {
            Kind.POSITIONAL -> Tok.Num(i, idx, run.value, orig, strong = true, never = false)
            Kind.DIGITS -> when {
                isSequential(run.text) -> Tok.Num(i, idx, "", orig, strong = false, never = true)
                run.text.length >= 3 -> Tok.Num(i, idx, run.value, orig, strong = true, never = false)
                else -> Tok.Num(i, idx, run.value, orig, strong = false, never = false)
            }
            else -> Tok.Num(i, idx, run.value, orig, strong = false, never = false)
        }
    }

    /** H點／H時…：判斷時間或小數。H 是點前的整數；[hourChar] 為真表示用的是「時」，不可能是小數。 */
    private fun matchPoint(s: String, hour: Run, pointIdx: Int, hourChar: Boolean): Tok.Num? {
        if (hour.kind == Kind.INVALID) return null
        val after = pointIdx + 1
        val h = hour.number
        val hourOk = h in 0..24 && (hour.kind != Kind.DIGITS || hour.text.length <= 2)
        val start = hour.start

        fun time(end: Int, minutes: Int, seconds: Int? = null): Tok.Num {
            val sec = if (seconds != null) ":" + seconds.toString().padStart(2, '0') else ""
            val value = "$h:" + minutes.toString().padStart(2, '0') + sec
            return Tok.Num(start, end, value, s.substring(start, end), strong = true, never = false)
        }

        if (hourOk) {
            if (s.startsWith("半", after)) return time(after + 1, 30)
            if (s.startsWith("整", after)) return time(after + 1, 0)
            if (!hourChar && (s.startsWith("鐘", after) || s.startsWith("钟", after))) return time(after + 1, 0)
        }

        val m = readRun(s, after) ?: return null
        if (m.kind == Kind.INVALID) return null
        val minutes = minutesOf(m)
        val isTen = m.kind == Kind.SINGLE && m.text == "十"

        if (hourOk && s.startsWith("刻", m.end) && m.kind == Kind.SINGLE && m.number in 1..3) {
            return time(m.end + 1, (15 * m.number).toInt())
        }
        if (hourOk && minutes != null && s.startsWith("分", m.end) && !s.startsWith("分之", m.end)) {
            var end = m.end + 1
            var seconds: Int? = null
            val secRun = readRun(s, end)
            if (secRun != null && secRun.kind != Kind.INVALID && s.startsWith("秒", secRun.end)) {
                minutesOf(secRun)?.let { seconds = it; end = secRun.end + 1 }
            }
            return time(end, minutes, seconds)
        }
        if (hourOk && minutes != null && (m.kind == Kind.POSITIONAL || isTen)) {
            return time(m.end, minutes)
        }
        if (hourOk && minutes != null && hasTimeCue(s, start) && (m.kind == Kind.SINGLE || m.kind == Kind.DIGITS)) {
            return time(m.end, minutes)
        }
        // 小數是逐位唸的：點後只能是單個數字或數字串
        if (!hourChar && ((m.kind == Kind.SINGLE && !isTen) || m.kind == Kind.DIGITS)) {
            return Tok.Num(start, m.end, hour.value + "." + m.value, s.substring(start, m.end), strong = true, never = false)
        }
        return null
    }

    private fun minutesOf(m: Run): Int? {
        val v = when (m.kind) {
            Kind.POSITIONAL -> m.number
            Kind.SINGLE -> m.number
            Kind.DIGITS -> m.value.toLongOrNull() ?: -1
            Kind.INVALID -> -1
        }
        return if (v in 0..59) v.toInt() else null
    }

    // ── 連續數字字元的解析 ────────────────────────────────────────────────

    private fun digitOf(c: Char): Int = when (c) {
        '零', '〇' -> 0
        '一' -> 1
        '二', '兩', '两' -> 2
        '三' -> 3
        '四' -> 4
        '五' -> 5
        '六' -> 6
        '七' -> 7
        '八' -> 8
        else -> 9
    }

    /** 連續遞增或遞減 1 的數字串（三四、一二三、三二一）是概數或數數，不當成數字。 */
    private fun isSequential(text: String): Boolean {
        if (text.length < 2) return false
        val d = text.map { digitOf(it) }
        val up = (1 until d.size).all { d[it] == d[it - 1] + 1 }
        val down = (1 until d.size).all { d[it] == d[it - 1] - 1 }
        return up || down
    }

    private fun readRun(s: String, from: Int): Run? {
        var j = from
        while (j < s.length) {
            val c = s[j]
            val ok = when {
                c in DIGIT_CHARS || c in UNIT_CHARS -> true
                // 「兩」只有在開頭或接在 百千萬億 之後才是數字，否則是量詞（三兩肉）
                c in TWO_CHARS -> j == from || s[j - 1] in BIG_UNITS
                else -> false
            }
            if (!ok) break
            j++
        }
        if (j == from) return null
        val text = s.substring(from, j)
        val hasUnit = text.any { it in UNIT_CHARS }
        if (!hasUnit) {
            val value = text.map { digitOf(it) }.joinToString("")
            val kind = if (text.length == 1) Kind.SINGLE else Kind.DIGITS
            return Run(from, j, text, kind, value, value.toLongOrNull() ?: -1)
        }
        if (text == "十") return Run(from, j, text, Kind.SINGLE, "10", 10)
        val n = parsePositional(text) ?: return Run(from, j, text, Kind.INVALID, "", -1)
        return Run(from, j, text, Kind.POSITIONAL, n.toString(), n)
    }

    /**
     * 解析含 十百千萬億 的整數。支援「零」補位（一百零三）與口語省略（三千五 = 3500、一萬二 = 12000）。
     * 結構不合法（千萬、百、十十）時回傳 null。
     */
    private fun parsePositional(text: String): Long? {
        if (text[0] == '零' || text[0] == '〇') return null
        var yi = 0L
        var wan = 0L
        var section = 0L
        var num = -1L
        var lastUnit = 0L
        var zeroSeen = false
        var blockStart = true
        for (c in text) {
            when {
                c in DIGIT_CHARS || c in TWO_CHARS -> {
                    if (num >= 0) return null
                    val d = digitOf(c)
                    if (d == 0) zeroSeen = true else num = d.toLong()
                }
                c == '十' || c == '百' || c == '千' -> {
                    val u = when (c) { '十' -> 10L; '百' -> 100L; else -> 1000L }
                    if (num < 0) {
                        if (c == '十' && blockStart && section == 0L) num = 1 else return null
                    }
                    section += num * u
                    num = -1
                    lastUnit = u
                    zeroSeen = false
                    blockStart = false
                }
                else -> {
                    val part = section + (if (num >= 0) num else 0)
                    if (part == 0L) return null
                    if (c == '萬' || c == '万') {
                        if (wan != 0L) return null
                        wan = part * 10_000L
                        lastUnit = 10_000L
                    } else {
                        if (yi != 0L) return null
                        yi = (wan + part) * 100_000_000L
                        wan = 0
                        lastUnit = 100_000_000L
                    }
                    section = 0
                    num = -1
                    zeroSeen = false
                    blockStart = true
                }
            }
        }
        var tail = if (num >= 0) num else 0L
        // 口語省略：最後一個數字接在 百千萬億 後面，代表下一個較小的單位
        if (num >= 0 && !zeroSeen && lastUnit >= 100L) tail = num * (lastUnit / 10)
        return yi + wan + section + tail
    }
}
