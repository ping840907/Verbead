package com.ping.verbead.ocr

import android.graphics.PointF
import android.graphics.RectF

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
            "[0-9]+[.\\u3001\\)]" +            // 1. or 1、 or 1)
            "|\\[[0-9]+\\]" +                  // [1]
            "|\\([0-9]+\\)" +                  // (1)
            "|[•·\\-*▪◆]" +                    // Bullet points
            "|[A-Za-z][.\\)]" +                // A. or a)
            "|[一二三四五六七八九十]+[、.]" +      // 一、 or 一.
            "|[(（][一二三四五六七八九十]+[)）]" + // （一） or (一)
        ")"
    )

    data class StitchConnection(
        val prevText: String,
        val separator: String
    )

    /**
     * Stitches OCR blocks into a single string by analyzing geometry, punctuation rules,
     * and Unicode language transitions.
     */
    fun stitch(blocks: List<OcrBlock>, fallbackSep: String = "\n"): String {
        if (blocks.isEmpty()) return ""
        if (blocks.size == 1) return blocks[0].text

        val sb = StringBuilder()
        for (i in 0 until blocks.size - 1) {
            val prev = blocks[i]
            val curr = blocks[i + 1]
            val conn = determineConnection(prev, curr, fallbackSep)
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
        fallbackSep: String = "\n"
    ): StitchConnection {
        val prevTrimmed = prev.text.trimEnd()
        val currTrimmed = curr.text.trimStart()

        if (prevTrimmed.isEmpty()) return StitchConnection(prev.text, fallbackSep)
        if (currTrimmed.isEmpty()) return StitchConnection(prev.text, fallbackSep)

        // ══════════════════════════════════════════════════════════════
        // 第一階段：幾何排版特徵判定（Geometry Filter - 優先級最高）
        // ══════════════════════════════════════════════════════════════

        // 1. 字高差異倍率（Height Ratio）
        if (prev.height > 0f && curr.height > 0f) {
            val ratio = prev.height / curr.height
            if (ratio > 1.25f || ratio < 0.8f) {
                return StitchConnection(prev.text, "\n")
            }
        }

        // 2. 垂直行距超標（Vertical Gap）
        if (prev.height > 0f) {
            val deltaY = curr.top - prev.bottom
            if (deltaY > 1.2f * prev.height || deltaY < -0.3f * prev.height) {
                return StitchConnection(prev.text, "\n")
            }
        }

        // 3. 居中對齊與短行包裝判定（Center Alignment & Slogan Check）
        val avgWidth = (prev.width + curr.width) / 2f
        if (avgWidth > 0f && prev.height > 0f && curr.height > 0f) {
            val centerDiff = Math.abs(prev.centerX - curr.centerX)
            if (centerDiff < 0.15f * avgWidth) {
                val leftDiff = Math.abs(curr.left - prev.left)
                val isShortPrev = prev.width < 7f * prev.height
                val isShortCurr = curr.width < 7f * curr.height
                if (leftDiff > 0.4f * prev.height || (isShortPrev && isShortCurr)) {
                    return StitchConnection(prev.text, "\n")
                }
            }
        }

        // 4. 顯著縮排（Indentation）
        if (prev.height > 0f) {
            val deltaX = curr.left - prev.left
            if (deltaX > 1.5f * prev.height) {
                return StitchConnection(prev.text, "\n")
            }
        }

        // ══════════════════════════════════════════════════════════════
        // 第二階段：符號與規則過濾（Rule-based Filter）
        // ══════════════════════════════════════════════════════════════

        // 1. 終止標點：[。！？!?；;\n]
        val lastChar = prevTrimmed.last()
        if (lastChar in "。！？!?；;\n") {
            return StitchConnection(prev.text, "\n")
        }

        // 2. 列表符號
        if (isListItem(currTrimmed)) {
            return StitchConnection(prev.text, "\n")
        }

        // 3. 英文連字號消除（Hyphen Unwrapping）
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

        val firstChar = currTrimmed.first()

        val sep = when {
            // 1. CJK 連接 CJK
            (isCjk(lastChar) || isCjkPunct(lastChar)) && isCjk(firstChar) -> ""

            // 2. Latin 連接 Latin
            isLatin(lastChar) && isLatin(firstChar) -> " "

            // 3. Hangul 連接 Hangul
            isHangul(lastChar) && isHangul(firstChar) -> " "

            // 4. CJK 與 Latin / 數字混排交界
            (isCjk(lastChar) && (isLatin(firstChar) || isDigit(firstChar))) ||
            ((isLatin(lastChar) || isDigit(lastChar)) && isCjk(firstChar)) -> ""

            // 5. 預設/其他字元（補空格防止沾黏）
            else -> " "
        }

        val baseText = if (sep.isEmpty() || sep == " ") prevTrimmed else prev.text
        return StitchConnection(baseText, sep)
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
