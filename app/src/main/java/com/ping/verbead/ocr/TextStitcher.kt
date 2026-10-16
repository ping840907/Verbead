package com.ping.verbead.ocr

import android.graphics.PointF
import android.graphics.RectF
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
    val centerY: Float get() = (top + bottom) / 2f
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
            "[0-9]+[.\\u3001\\)]" +            // 1. or 1、 or 1)
            "|\\[[0-9]+\\]" +                  // [1]
            "|\\([0-9]+\\)" +                  // (1)
            "|[•·\\-*▪◆]" +                    // Bullet points
            "|[A-Za-z][.\\)]" +                // A. or a)
            "|[一二三四五六七八九十]+[、.]" +      // 一、 or 一.
            "|[(（][一二三四五六七八九十]+[)）]" + // （一） or (一)
        ")"
    )

    // 終止標點符號集合（包含使用者要求的 ： 與 :）
    private val TERMINAL_PUNCTS = setOf('。', '！', '？', '!', '?', '；', ';', '：', ':', '\n')

    // 閉合括號與引號
    private val CLOSING_CHARS = setOf('」', '』', '”', '’', '"', '\'', '）', ')', '】', ']', '｝', '}', '》', '>')

    // 開放括號與引號
    private val OPENING_CHARS = setOf('「', '『', '“', '‘', '（', '(', '【', '[', '｛', '{', '《', '<')

    // 英文常見縮寫（小寫）
    private val COMMON_ABBREVIATIONS = setOf(
        "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "vs", "etc", "eg", "ie",
        "fig", "al", "no", "inc", "ltd", "co", "corp", "dept", "gen", "rep",
        "sen", "gov", "approx", "avg", "est", "min", "max"
    )

    data class StitchConnection(
        val prevText: String,
        val separator: String
    )

    /**
     * Stitches OCR blocks into a single string by analyzing geometry, punctuation rules,
     * and Unicode language transitions.
     */
    fun stitch(
        blocks: List<OcrBlock>,
        fallbackSep: String = "\n",
        panguSpacing: Boolean = false
    ): String {
        if (blocks.isEmpty()) return ""
        if (blocks.size == 1) return blocks[0].text

        val maxLineWidth = blocks.maxOfOrNull { it.width } ?: 0f

        val sb = StringBuilder()
        for (i in 0 until blocks.size - 1) {
            val prev = blocks[i]
            val curr = blocks[i + 1]
            val conn = determineConnection(
                prev = prev,
                curr = curr,
                fallbackSep = fallbackSep,
                maxLineWidth = maxLineWidth,
                panguSpacing = panguSpacing
            )
            sb.append(conn.prevText)
            sb.append(conn.separator)
        }
        sb.append(blocks.last().text)
        return sb.toString()
    }

    /**
     * Evaluates connection between adjacent [prev] and [curr] lines.
     */
    fun determineConnection(
        prev: OcrBlock,
        curr: OcrBlock,
        fallbackSep: String = "\n",
        maxLineWidth: Float = 0f,
        panguSpacing: Boolean = false
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
        if (prev.height > 0f) {
            val deltaY = curr.top - prev.bottom
            if (deltaY > 1.2f * prev.height || deltaY < -0.3f * prev.height) {
                return StitchConnection(prev.text, "\n")
            }
        }

        // 5. 居中對齊與短行包裝判定（Center Alignment & Slogan Check）
        val avgWidth = (prev.width + curr.width) / 2f
        if (avgWidth > 0f && prev.height > 0f && curr.height > 0f) {
            val centerDiff = abs(prev.centerX - curr.centerX)
            if (centerDiff < 0.15f * avgWidth) {
                val leftDiff = abs(curr.left - prev.left)
                val isShortPrev = prev.width < 7f * prev.height
                val isShortCurr = curr.width < 7f * curr.height
                if (leftDiff > 0.4f * prev.height || (isShortPrev && isShortCurr)) {
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
                if (firstChar.isUpperCase() || !firstChar.isLetter()) {
                    return StitchConnection(prev.text, "\n")
                }
            }
        }

        // 3. 列表符號
        if (isListItem(currTrimmed)) {
            return StitchConnection(prev.text, "\n")
        }

        // 4. 英文連字號消除（Hyphen Unwrapping）
        if (prevTrimmed.endsWith('-') && prevTrimmed.length >= 2) {
            val charBeforeHyphen = prevTrimmed[prevTrimmed.length - 2]
            val firstChar = currTrimmed.first()
            if (isLatin(charBeforeHyphen) && isLatin(firstChar)) {
                return StitchConnection(prevTrimmed.dropLast(1), "")
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

            // 4. CJK 與 Latin / 數字混排交界
            (isCjk(lastChar) && (isLatin(firstChar) || isDigit(firstChar))) ||
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
               (code in 0x0100..0x024F)      // Latin Extended-A & B
    }

    internal fun isHangul(c: Char): Boolean {
        val code = c.code
        return (code in 0xAC00..0xD7AF) ||   // Hangul Syllables
               (code in 0x1100..0x11FF) ||   // Hangul Jamo
               (code in 0x3130..0x318F)      // Hangul Compatibility Jamo
    }

    internal fun isDigit(c: Char): Boolean {
        return c in '0'..'9' || c in '\uFF10'..'\uFF19'
    }
}
