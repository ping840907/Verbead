package com.ping.verbead.ocr

import android.graphics.PointF
import android.graphics.RectF
import com.ping.verbead.engine.ModelConfig
import com.ping.verbead.engine.lang.ZhTwRules
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Represents a single OCR text block with recognized text and bounding box coordinates.
 */
data class OcrBlock(
    val text: String,
    // 順時針四點座標：左上, 右上, 右下, 左下
    val box: List<PointF> = emptyList(),
    val left: Float = if (box.isNotEmpty()) box.minOf { it.x } else 0f,
    val top: Float = if (box.isNotEmpty()) box.minOf { it.y } else 0f,
    val right: Float = if (box.isNotEmpty()) box.maxOf { it.x } else 0f,
    val bottom: Float = if (box.isNotEmpty()) box.maxOf { it.y } else 0f
) {
    constructor(text: String, rect: RectF) : this(
        text = text,
        box = listOf(
            PointF(rect.left, rect.top).apply { x = rect.left; y = rect.top },
            PointF(rect.right, rect.top).apply { x = rect.right; y = rect.top },
            PointF(rect.right, rect.bottom).apply { x = rect.right; y = rect.bottom },
            PointF(rect.left, rect.bottom).apply { x = rect.left; y = rect.bottom }
        ),
        left = rect.left,
        top = rect.top,
        right = rect.right,
        bottom = rect.bottom
    )

    constructor(text: String, left: Float, top: Float, right: Float, bottom: Float) : this(
        text = text,
        box = listOf(
            PointF(left, top).apply { x = left; y = top },
            PointF(right, top).apply { x = right; y = top },
            PointF(right, bottom).apply { x = right; y = bottom },
            PointF(left, bottom).apply { x = left; y = bottom }
        ),
        left = left,
        top = top,
        right = right,
        bottom = bottom
    )

    val height: Float get() = (bottom - top).coerceAtLeast(0f)
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val centerX: Float get() = (left + right) / 2f
}

/**
 * Android 端側 PP-OCR 文字折行縫合與邊界判定引擎 (TextStitcher)。
 *
 * 智慧判斷相鄰兩行文字是否為自然折行：
 * - 自然折行：消除多餘的分隔符（CJK 無縫貼合、英文/韓文補單一空格、連字號消除）。
 * - 獨立段落、標題、列表或廣告包裝短語：強制保留換行 \n。
 */
object TextStitcher {

    private val LIST_ITEM_REGEX = Regex(
        "^(" +
            // \p{Nd} 涵蓋全形、阿拉伯-印度、天城文、泰文等各種十進位數字
            "\\p{Nd}+[.\\u3001\\)](?!\\p{Nd})" + // 1. or 1、 or 1)（排除 12.5、3.5mm 這類小數）
            "|[\\[［]\\p{Nd}+[\\]］]" +          // [1]
            "|[(（]\\p{Nd}+[)）]" +              // (1) or （１）
            "|[•·▪◆\\u30FB※◎○●□■△▲▼▽★☆]" +    // 項目符號（含日文中點 U+30FB、※ 與各式圓點方塊）
            "|[\\u2460-\\u2473\\u3220-\\u3229\\u3251-\\u325F\\u32B1-\\u32BF]" + // ① ⑳ ㈠ ㉑ 圈號
            "|[\\-*](?![0-9\\-*])" +             // - 或 * 項目符號（排除 -5°C 這類負號）
            // 單一字母編號 A. a) 與俄文 а) 希臘文 α)；後面要有空白或中日文（排除 U.S.、e.g.）
            "|[\\p{IsLatin}\\p{IsCyrillic}\\p{IsGreek}][.\\)](?=[\\s\\u3000\\u4E00-\\u9FFF]|$)" +
            // 韓文 가. 나. 다. 라. 마. 바. 사. 아. 자. 차. 카. 타. 파. 하.（以 \u 跳脫寫，避免寫死 CJK 字面量）
            "|[\\uAC00\\uB098\\uB2E4\\uB77C\\uB9C8\\uBC14\\uC0AC\\uC544\\uC790\\uCC28\\uCE74\\uD0C0\\uD30C\\uD558][.\\)]" +
            "|[一二三四五六七八九十]+[、.]" +      // 一、 or 一.
            "|[(（][一二三四五六七八九十]+[)）]" + // （一） or (一)
        ")"
    )

    // 終止標點符號集合：中日（。！？；：）、半形、阿拉伯文（؟ ۔ ؛）、印度文（। ॥）、半形日文句點
    private val TERMINAL_PUNCTS = setOf(
        '。', '！', '？', '!', '?', '；', ';', '：', ':', '\n',
        '｡', '．', '‼', '⁇', '⁈', '⁉', '؟', '۔', '؛', '।', '॥'
    )

    // 數字、英文之前常見的符號（負號、貨幣、標籤、帳號）：與 CJK 交界時比照英數字處理
    private val LEADING_SIGN_CHARS = setOf('-', '+', '$', '#', '@', '~', '%')

    // 行尾連字號：ASCII 連字號、U+2010、U+2011、軟連字號
    private val HYPHEN_CHARS = setOf('-', '‐', '‑', '­')

    // 逗號類字元：行尾或行首出現時視為句子未結束
    private val COMMA_CHARS = setOf('，', ',', '、', '､', '،', '﹐', '﹑')

    // 逗號連接時容許的字高倍率與行距上限（比一般斷行規則寬鬆）
    private const val COMMA_MAX_HEIGHT_RATIO = 1.6f

    /**
     * 偵測框只框到字形的七成左右，而網頁/App 內文的行高常是字高的兩倍，所以「行距 = 框高的 1.2 倍」這種固定倍率
     * 會把正常的自然折行全部切開（實測：框高 33 px、行距 62 px、倍率 1.9）。因此改成：
     * - 整頁有足夠的行可以量時，以「典型行距」（相鄰兩行上緣距離的中位數）為準，只有超過它的 [PITCH_BREAK_RATIO] 倍才算段落間距；
     * - 量不到時（只有兩三行），退回較寬鬆的固定倍率 [FALLBACK_MAX_GAP]。
     * 超過舊上限（1.2 倍框高）的寬鬆放行，還要求前一行夠長（至少最寬行的 80%，[WRAP_MIN_WIDTH_RATIO]），避免把短行的選單、列表接在一起。
     */
    private const val STRICT_MAX_GAP = 1.2f
    private const val FALLBACK_MAX_GAP = 2.2f
    private const val COMMA_FALLBACK_MAX_GAP = 2.6f
    private const val PITCH_BREAK_RATIO = 1.5f
    private const val COMMA_PITCH_BREAK_RATIO = 2.0f
    private const val WRAP_MIN_WIDTH_RATIO = 0.8f
    private const val MAX_NATURAL_PITCH_IN_HEIGHTS = 3.3f
    private const val MIN_PITCH_SAMPLES = 3

    // 閉合括號與引號
    private val CLOSING_CHARS = setOf(
        '」', '』', '”', '’', '"', '\'', '）', ')', '】', ']', '｝', '}', '》', '>',
        '»', '›', '〉', '〕', '］'
    )

    // 開放括號與引號
    private val OPENING_CHARS = setOf(
        '「', '『', '“', '‘', '（', '(', '【', '[', '｛', '{', '《', '<',
        '«', '‹', '„', '‚', '〈', '〔', '［'
    )

    // 常見縮寫（小寫、不含結尾句點）：英、德、法、西、葡、意、俄。俄文縮寫內含句點（т.д.），所以也列出帶點的形式
    private val COMMON_ABBREVIATIONS = setOf(
        // English
        "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "vs", "etc", "eg", "ie", "e.g", "i.e",
        "fig", "al", "no", "inc", "ltd", "co", "corp", "dept", "gen", "rep",
        "sen", "gov", "approx", "avg", "est", "min", "max",
        // Deutsch
        "z.b", "d.h", "u.a", "bzw", "usw", "ggf", "ca", "nr", "hr", "fr", "str", "dipl", "ing", "vgl", "evtl", "inkl", "zzgl",
        // Français
        "mme", "mlle", "mm", "cf", "env", "p.ex", "av", "bd", "boul",
        // Español / Português / Italiano
        "sra", "srta", "dra", "ud", "uds", "sig", "dott", "avv", "ecc", "pág", "pag", "tel", "núm", "exmo",
        // Русский
        "т.д", "т.п", "т.е", "т.к", "и т.д", "см", "рис", "стр", "им", "ул", "г", "гг", "тыс", "млн", "млрд", "руб", "коп"
    )

    data class StitchConnection(
        val prevText: String,
        val separator: String
    )

    /**
     * 估計這一頁的典型行距（相鄰兩行上緣的距離，中位數）。只取同一欄、字高相近、由上往下的相鄰行；
     * 樣本少於 [MIN_PITCH_SAMPLES] 個、或行距超過 [MAX_NATURAL_PITCH_IN_HEIGHTS] 倍框高（那更像是一份份分開的項目，不是折行）時回傳 0。
     */
    fun estimateLinePitch(blocks: List<OcrBlock>): Float {
        val pitches = ArrayList<Float>()
        val heights = ArrayList<Float>()
        for (i in 1 until blocks.size) {
            val prev = blocks[i - 1]
            val curr = blocks[i]
            if (prev.height <= 0f || curr.height <= 0f) continue
            val pitch = curr.top - prev.top
            if (pitch <= 0f) continue
            val ratio = prev.height / curr.height
            if (ratio > 1.25f || ratio < 0.8f) continue
            val overlapX = min(prev.right, curr.right) - max(prev.left, curr.left)
            if (overlapX < 0.5f * min(prev.width, curr.width)) continue
            pitches.add(pitch)
            heights.add(prev.height)
        }
        if (pitches.size < MIN_PITCH_SAMPLES) return 0f
        pitches.sort()
        heights.sort()
        val pitch = pitches[pitches.size / 2]
        val height = heights[heights.size / 2]
        return if (pitch > MAX_NATURAL_PITCH_IN_HEIGHTS * height) 0f else pitch
    }

    /** [curr] 離 [prev] 的行距仍在自然折行的範圍內嗎？（[pitchRatio]：有典型行距時的倍率；[fallbackGap]：沒有時的框高倍率） */
    private fun withinNaturalSpacing(
        prev: OcrBlock,
        curr: OcrBlock,
        linePitch: Float,
        pitchRatio: Float,
        fallbackGap: Float
    ): Boolean =
        if (linePitch > 0f) {
            (curr.top - prev.top) <= pitchRatio * linePitch
        } else {
            (curr.top - prev.bottom) <= fallbackGap * prev.height
        }

    /**
     * Stitches OCR blocks into a single string by analyzing geometry, punctuation rules,
     * and Unicode language transitions.
     */
    fun stitch(
        blocks: List<OcrBlock>,
        fallbackSep: String = "\n",
        panguSpacing: Boolean = false,
        toTraditional: Boolean = false
    ): String {
        if (blocks.isEmpty()) return ""
        val initial = if (blocks.size == 1) {
            if (panguSpacing) applyPanguSpacing(blocks[0].text) else blocks[0].text
        } else {
            val maxLineWidth = blocks.maxOfOrNull { it.width } ?: 0f
            val linePitch = estimateLinePitch(blocks)

            val sb = StringBuilder()
            // acc: 目前累積中的最後一行（文字可能已併入網址續行，幾何沿用最後一行）
            var acc = blocks[0]
            for (i in 1 until blocks.size) {
                val curr = blocks[i]
                val join = joinUrlWrap(acc, curr, maxLineWidth, linePitch)
                if (join != null) {
                    if (join.rest.isEmpty()) {
                        acc = OcrBlock(join.urlText, curr.left, curr.top, curr.right, curr.bottom)
                    } else {
                        // 網址之後的文字屬於另一段內容：只用空白接在後面，絕不併入網址
                        sb.append(join.urlText).append(" ")
                        acc = OcrBlock(join.rest, curr.left, curr.top, curr.right, curr.bottom)
                    }
                    continue
                }
                val conn = determineConnection(
                    prev = acc,
                    curr = curr,
                    fallbackSep = fallbackSep,
                    maxLineWidth = maxLineWidth,
                    panguSpacing = panguSpacing,
                    linePitch = linePitch
                )
                sb.append(conn.prevText)
                sb.append(conn.separator)
                acc = curr
            }
            sb.append(acc.text)
            val result = sb.toString()
            if (panguSpacing) applyPanguSpacing(result) else result
        }
        return if (toTraditional) ZhTwRules.toTaiwanTraditional(initial) else initial
    }

    private val URL_START_REGEX = Regex(
        "^(?:[A-Za-z][A-Za-z0-9+.-]*://|www\\.|[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+/)\\S*$",
        RegexOption.IGNORE_CASE
    )
    private val URL_CHARS_REGEX = Regex("^[A-Za-z0-9\\-._~:/?#\\[\\]@!$&'()*+,;=%]+$")

    /** URL 折行縫合結果：[urlText] 為已接上續段的文字，[rest] 為續段之後與網址無關的剩餘文字。 */
    data class UrlJoin(val urlText: String, val rest: String)

    /**
     * 判斷 [prev] 是否以被截斷的網址結尾，且 [curr] 開頭是該網址的續段。
     * 是則回傳消除分行分隔後的網址，續段只取 [curr] 的第一個以空白分隔的字詞，
     * 其餘文字原樣放在 rest，確保網址不會吃到不相干的文字。
     *
     * 判斷條件（全部成立才縫合）：
     * 1. prev 最後一個字詞像網址開頭（協定://、www.、網域/路徑），且全是網址合法字元。
     * 2. prev 這行接近最寬行寬（網址被迫在行尾斷開）；沒有幾何資料時改看是否停在 / - . ? & = # _ % 等斷點。
     * 3. curr 與 prev 的版面是相連的下一行（未向上跳躍、字高相近、行距正常），且不是列表項目。
     * 4. curr 第一個字詞只含網址合法字元；prev 以句點結尾而 curr 以大寫字母開頭時視為新句子，不縫合。
     */
    fun joinUrlWrap(prev: OcrBlock, curr: OcrBlock, maxLineWidth: Float, linePitch: Float = 0f): UrlJoin? {
        val prevT = prev.text.trimEnd()
        val currT = curr.text.trimStart()
        if (prevT.isEmpty() || currT.isEmpty()) return null

        val prevToken = prevT.split(Regex("[\\s\u3000]+")).last()
        if (!URL_START_REGEX.matches(prevToken) || !URL_CHARS_REGEX.matches(prevToken)) return null

        val wsIdx = currT.indexOfFirst { it.isWhitespace() || it == '\u3000' }
        val token = if (wsIdx < 0) currT else currT.substring(0, wsIdx)
        val rest = if (wsIdx < 0) "" else currT.substring(wsIdx).trim()
        if (token.isEmpty() || !URL_CHARS_REGEX.matches(token)) return null
        if (isListItem(currT)) return null
        if (prevToken.endsWith('.') && token.first().isUpperCase()) return null

        val hasGeometry = prev.height > 0f && curr.height > 0f && maxLineWidth > 0f
        if (hasGeometry) {
            if (prev.width < maxLineWidth * 0.85f) return null
            if (curr.top < prev.top - 0.5f * prev.height) return null
            val ratio = prev.height / curr.height
            if (ratio > 1.25f || ratio < 0.8f) return null
            val deltaY = curr.top - prev.bottom
            if (deltaY < -0.3f * prev.height) return null
            if (deltaY > STRICT_MAX_GAP * prev.height &&
                !withinNaturalSpacing(prev, curr, linePitch, PITCH_BREAK_RATIO, FALLBACK_MAX_GAP)
            ) return null
        } else if (prevToken.last() !in "/-._?&=#%+~:,") {
            return null
        }

        return UrlJoin(prevT + token, rest)
    }

    /**
     * Evaluates connection between adjacent [prev] and [curr] lines.
     */
    fun determineConnection(
        prev: OcrBlock,
        curr: OcrBlock,
        fallbackSep: String = "\n",
        maxLineWidth: Float = 0f,
        panguSpacing: Boolean = false,
        linePitch: Float = 0f
    ): StitchConnection {
        val prevTrimmed = prev.text.trimEnd()
        val currTrimmed = curr.text.trimStart()

        if (prevTrimmed.isEmpty()) return StitchConnection(prev.text, fallbackSep)
        if (currTrimmed.isEmpty()) return StitchConnection(prev.text, fallbackSep)

        // ══════════════════════════════════════════════════════════════
        // 第一階段：幾何排版特徵判定（Geometry Filter - 優先級最高）
        // ══════════════════════════════════════════════════════════════

        val minH = min(prev.height, curr.height)
        val overlapY = if (minH > 0f) max(0f, min(prev.bottom, curr.bottom) - max(prev.top, curr.top)) else 0f

        // 1. 同列橫向相鄰文字框判定（Horizontal Inline Check - 解決同一行文字框被誤判換行）
        val isHorizontalInline = minH > 0f && overlapY > 0.5f * minH && curr.left >= (prev.left - 0.2f * minH)
        if (isHorizontalInline) {
            val deltaX = curr.left - prev.right
            val lastC = prevTrimmed.last()
            val firstC = currTrimmed.first()

            val sep = when {
                lastC in OPENING_CHARS || firstC in CLOSING_CHARS -> ""
                deltaX > 0.35f * minH -> " "
                else -> determineUnicodeSep(lastC, firstC, panguSpacing)
            }
            return StitchConnection(prevTrimmed, sep)
        }

        // 1.5 逗號連接（Comma Continuation）：行尾是逗號、或下一行以逗號開頭，代表句子還沒說完，
        // 優先於縮排、居中短行、行距、字高等版面規則接成同一句；只有明確的多欄跳行、
        // 行距遠超標、字高差距過大，或下一行是列表項目時才仍然斷行。
        commaConnection(prev, curr, prevTrimmed, currTrimmed, panguSpacing, linePitch)?.let { return it }

        // 2. 多欄排版與 Y 軸向上跳躍判定（Multi-column & Upward Jump Safeguard）
        if (prev.height > 0f && curr.top < prev.top - 0.5f * prev.height) {
            return StitchConnection(prev.text, "\n")
        }

        // 3. 字高差異倍率（Height Ratio）
        if (prev.height > 0f && curr.height > 0f) {
            val ratio = prev.height / curr.height
            if (ratio > 1.25f || ratio < 0.8f) {
                return StitchConnection(prev.text, "\n")
            }
        }

        // 4. 垂直行距超標（Vertical Gap）
        //    超過 1.2 倍框高時，只有「前一行夠長、行距仍符合這一頁的典型行距」才當成自然折行（見 STRICT_MAX_GAP 的說明）
        if (prev.height > 0f) {
            val deltaY = curr.top - prev.bottom
            if (deltaY < -0.3f * prev.height) {
                return StitchConnection(prev.text, "\n")
            }
            if (deltaY > STRICT_MAX_GAP * prev.height) {
                val looksWrapped = maxLineWidth <= 0f || prev.width >= WRAP_MIN_WIDTH_RATIO * maxLineWidth
                if (!looksWrapped || !withinNaturalSpacing(prev, curr, linePitch, PITCH_BREAK_RATIO, FALLBACK_MAX_GAP)) {
                    return StitchConnection(prev.text, "\n")
                }
            }
        }

        // 5. 居中對齊與短行包裝判定（Center Alignment & Slogan Check）
        val avgWidth = (prev.width + curr.width) / 2f
        if (avgWidth > 0f && prev.height > 0f && curr.height > 0f) {
            val centerDiff = abs(prev.centerX - curr.centerX)
            if (centerDiff < 0.15f * avgWidth) {
                val leftDelta = curr.left - prev.left
                val rightDelta = curr.right - prev.right
                // 真正的居中：左右邊界往相反方向各退一段明顯的距離。首行縮排只有左邊界不同、
                // 右邊界對齊（實拍照片右緣會有幾個像素的雜訊，方向可能剛好相反），中心點雖然接近，
                // 但不是居中，必須和下一行接起來
                val edgeThreshold = 0.4f * prev.height
                val edgesMoveOpposite = leftDelta * rightDelta < 0f &&
                    abs(leftDelta) > edgeThreshold && abs(rightDelta) > edgeThreshold
                val isShortPrev = prev.width < 7f * prev.height
                val isShortCurr = curr.width < 7f * curr.height
                if (edgesMoveOpposite || (isShortPrev && isShortCurr)) {
                    return StitchConnection(prev.text, "\n")
                }
            }
        }

        // 6. 顯著縮排（Indentation）
        if (prev.height > 0f) {
            val deltaX = curr.left - prev.left
            if (deltaX > 1.5f * prev.height) {
                return StitchConnection(prev.text, "\n")
            }
        }

        // 7. 段落行末留白特徵（Paragraph End Margin）
        if (maxLineWidth > 0f && prev.height > 0f && prev.width < maxLineWidth * 0.68f) {
            val deltaY = curr.top - prev.bottom
            if (deltaY >= 0f) {
                val stripped = stripClosingPunctuation(prevTrimmed)
                val lastCharBeforeClosing = stripped.lastOrNull()
                if (lastCharBeforeClosing != null && (lastCharBeforeClosing in TERMINAL_PUNCTS || lastCharBeforeClosing == '.')) {
                    return StitchConnection(prev.text, "\n")
                }
            }
        }

        // ══════════════════════════════════════════════════════════════
        // 第二階段：符號與規則過濾（Rule-based Filter）
        // ══════════════════════════════════════════════════════════════

        // 1. 複合終止標點：[。！？!?；;:：\n]（支援剝離右側引號與閉合括號）
        val strippedPrev = stripClosingPunctuation(prevTrimmed)
        val effectiveLastChar = strippedPrev.lastOrNull()

        if (effectiveLastChar != null && effectiveLastChar in TERMINAL_PUNCTS) {
            return StitchConnection(prev.text, "\n")
        }

        // 2. 英文句號（Period .）與縮寫消歧義
        if (effectiveLastChar == '.') {
            val isAbbr = isAbbreviation(strippedPrev)
            val firstChar = currTrimmed.first()
            if (!isAbbr) {
                // 小寫字母開頭才視為句子延續；大寫、非字母，以及沒有大小寫的文字（阿拉伯文、希伯來文、
                // 中日韓、泰文等）都當成新句子的開頭
                if (!firstChar.isLowerCase()) {
                    return StitchConnection(prev.text, "\n")
                }
            }
        }

        // 3. 列表符號
        if (isListItem(currTrimmed)) {
            return StitchConnection(prev.text, "\n")
        }

        // 4. 連字號消除（Hyphen Unwrapping）：拉丁、西里爾、希臘等以空格分詞的文字，
        // 行尾連字號（含 U+2010、U+2011、軟連字號）把單字拆成兩段時接回去。
        // 下一行以大寫開頭多半是專有複合詞（Berlin-Mitte、Spider-Man），保留連字號不補空格。
        if (prevTrimmed.last() in HYPHEN_CHARS && prevTrimmed.length >= 2) {
            val hyphen = prevTrimmed.last()
            val charBeforeHyphen = prevTrimmed[prevTrimmed.length - 2]
            val firstChar = currTrimmed.first()
            if (isSpaceDelimitedLetter(charBeforeHyphen) && isSpaceDelimitedLetter(firstChar)) {
                return if (hyphen != '­' && firstChar.isUpperCase()) {
                    StitchConnection(prevTrimmed, "")
                } else {
                    StitchConnection(prevTrimmed.dropLast(1), "")
                }
            }
        }

        // ══════════════════════════════════════════════════════════════
        // 第三階段：Unicode 語系轉移狀態機（Cross-language Stitching）
        // ══════════════════════════════════════════════════════════════

        val lastChar = prevTrimmed.last()
        val firstChar = currTrimmed.first()

        // 標點防空格守衛：若前字是開括號，或後字是閉括號/標點，不插入多餘空格
        if (lastChar in OPENING_CHARS || firstChar in CLOSING_CHARS) {
            return StitchConnection(prevTrimmed, "")
        }

        val sep = determineUnicodeSep(lastChar, firstChar, panguSpacing)
        val baseText = if (sep.isEmpty() || sep == " ") prevTrimmed else prev.text
        return StitchConnection(baseText, sep)
    }

    /**
     * 逗號連接判定。[prevTrimmed] 以逗號結尾（可帶右引號/括號）或 [currTrimmed] 以逗號開頭時，
     * 若版面仍是相連的下一行就回傳接合結果，否則回傳 null 交給後續規則處理。
     */
    private fun commaConnection(
        prev: OcrBlock,
        curr: OcrBlock,
        prevTrimmed: String,
        currTrimmed: String,
        panguSpacing: Boolean,
        linePitch: Float
    ): StitchConnection? {
        val prevEndsWithComma = stripClosingPunctuation(prevTrimmed).lastOrNull()?.let { it in COMMA_CHARS } == true
        val currStartsWithComma = currTrimmed.first() in COMMA_CHARS
        if (!prevEndsWithComma && !currStartsWithComma) return null

        // 列表項目是新的一條，不接
        if (!currStartsWithComma && isListItem(currTrimmed)) return null

        // 版面仍須是相連的下一行：多欄跳行、行距遠超標、字高差距過大都不接
        if (prev.height > 0f && curr.height > 0f) {
            if (curr.top < prev.top - 0.5f * prev.height) return null
            val ratio = prev.height / curr.height
            if (ratio > COMMA_MAX_HEIGHT_RATIO || ratio < 1f / COMMA_MAX_HEIGHT_RATIO) return null
            val deltaY = curr.top - prev.bottom
            if (deltaY < -0.3f * prev.height) return null
            if (!withinNaturalSpacing(prev, curr, linePitch, COMMA_PITCH_BREAK_RATIO, COMMA_FALLBACK_MAX_GAP)) return null
        }

        val lastC = prevTrimmed.last()
        val firstC = currTrimmed.first()
        val sep = when {
            currStartsWithComma -> ""
            firstC in CLOSING_CHARS -> ""
            // 全形逗號、頓號後面不補空格（中英混排的空格交給盤古之白）
            lastC == '，' || lastC == '、' -> if (panguSpacing && isLatin(firstC)) " " else ""
            // 數字千分位被折行：1, + 000 -> 1,000
            lastC == ',' && prevTrimmed.length >= 2 && isDigit(prevTrimmed[prevTrimmed.length - 2]) && isDigit(firstC) -> ""
            // 半形逗號後接中日文
            lastC == ',' && isCjk(firstC) -> ""
            else -> " "
        }
        return StitchConnection(prevTrimmed, sep)
    }

    internal fun stripClosingPunctuation(text: String): String {
        var end = text.length
        while (end > 0 && text[end - 1] in CLOSING_CHARS) {
            end--
        }
        return text.substring(0, end)
    }

    internal fun isAbbreviation(text: String): Boolean {
        val lastWord = text.split(Regex("[\\s,;]+")).lastOrNull() ?: return false
        val cleaned = lastWord.trimEnd('.').lowercase()
        if (cleaned in COMMON_ABBREVIATIONS) return true
        if (cleaned.length == 1 && cleaned[0].isLetter()) return true
        return false
    }

    internal fun determineUnicodeSep(lastChar: Char, firstChar: Char, panguSpacing: Boolean): String {
        return when {
            // 1. CJK 連接 CJK
            (isCjk(lastChar) || isCjkPunct(lastChar)) && isCjk(firstChar) -> ""

            // 2. Latin 連接 Latin
            isLatin(lastChar) && isLatin(firstChar) -> " "

            // 3. Hangul 連接 Hangul
            isHangul(lastChar) && isHangul(firstChar) -> " "

            // 3.5 泰文、寮文、高棉文、緬文、藏文：詞與詞之間不用空格，換行處直接貼合
            isNoSpaceScript(lastChar) && isNoSpaceScript(firstChar) -> ""

            // 4. CJK 與 Latin / 數字混排交界
            (isCjk(lastChar) && (isLatin(firstChar) || isDigit(firstChar) || firstChar in LEADING_SIGN_CHARS)) ||
            ((isLatin(lastChar) || isDigit(lastChar)) && isCjk(firstChar)) -> {
                if (panguSpacing) " " else ""
            }

            // 5. 預設/其他字元（補空格防止沾黏）
            else -> " "
        }
    }

    internal fun isListItem(text: String): Boolean = LIST_ITEM_REGEX.containsMatchIn(text)

    internal fun isCjk(c: Char): Boolean {
        val code = c.code
        return (code in 0x4E00..0x9FFF) ||   // CJK Unified Ideographs
               (code in 0x3400..0x4DBF) ||   // CJK Extension A
               (code in 0xF900..0xFAFF) ||   // CJK Compatibility Ideographs
               (code in 0x3040..0x309F) ||   // Hiragana
               (code in 0x30A0..0x30FF) ||   // Katakana
               (code in 0x3100..0x312F) ||   // Bopomofo
               (code in 0x31A0..0x31BF)      // Bopomofo Extended
    }

    internal fun isCjkPunct(c: Char): Boolean {
        return c == '，' || c == '、' || c == '：' || c == '—' || c == '～' || c == '…'
    }

    internal fun isLatin(c: Char): Boolean {
        val code = c.code
        return (code in 0x0041..0x005A) ||   // A-Z
               (code in 0x0061..0x007A) ||   // a-z
               (code in 0x00C0..0x00FF) ||   // Latin-1 Supplement letters
               (code in 0x0100..0x024F) ||   // Latin Extended-A & B
               (code in 0x1E00..0x1EFF)      // Latin Extended Additional（越南語聲調字母等）
    }

    /** 不用空格分詞的文字：泰文、寮文、藏文、緬文、高棉文。 */
    internal fun isNoSpaceScript(c: Char): Boolean {
        val code = c.code
        return (code in 0x0E00..0x0EFF) ||   // Thai, Lao
               (code in 0x0F00..0x0FFF) ||   // Tibetan
               (code in 0x1000..0x109F) ||   // Myanmar
               (code in 0x1780..0x17FF)      // Khmer
    }

    /** 以空格分詞的字母（拉丁、西里爾、希臘、亞美尼亞、喬治亞等），排除中日韓與不用空格的文字。 */
    internal fun isSpaceDelimitedLetter(c: Char): Boolean =
        c.isLetter() && !isCjk(c) && !isHangul(c) && !isNoSpaceScript(c)

    internal fun isHangul(c: Char): Boolean {
        val code = c.code
        return (code in 0xAC00..0xD7AF) ||   // Hangul Syllables
               (code in 0x1100..0x11FF) ||   // Hangul Jamo
               (code in 0x3130..0x318F)      // Hangul Compatibility Jamo
    }

    fun applyPanguSpacing(text: String): String {
        if (text.isEmpty()) return ""
        val sb = StringBuilder(text.length + 16)
        val len = text.length
        for (i in 0 until len) {
            val c = text[i]
            sb.append(c)
            if (i < len - 1) {
                val nextC = text[i + 1]
                if (shouldInsertPanguSpace(c, nextC)) {
                    sb.append(' ')
                }
            }
        }
        return sb.toString()
    }

    private fun shouldInsertPanguSpace(curr: Char, next: Char): Boolean {
        if (curr.isWhitespace() || next.isWhitespace()) return false
        if (curr in OPENING_CHARS || next in CLOSING_CHARS) return false
        if (curr in CLOSING_CHARS && !isCjk(curr)) return false
        if (next in OPENING_CHARS && !isCjk(next)) return false
        if (isCjkPunct(curr) || isCjkPunct(next)) return false
        if (curr in TERMINAL_PUNCTS || next in TERMINAL_PUNCTS) return false

        if (isCjk(curr) && (isLatin(next) || isDigit(next))) return true
        if ((isLatin(curr) || isDigit(curr)) && isCjk(next)) return true

        return false
    }

    internal fun isDigit(c: Char): Boolean {
        return c in '0'..'9' || c in '\uFF10'..'\uFF19' || (c.code > 0x7F && Character.isDigit(c))
    }
}
