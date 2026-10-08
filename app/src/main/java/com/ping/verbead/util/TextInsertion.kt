package com.ping.verbead.util

/**
 * Pure string and cursor calculation helper for text insertion into editable fields.
 * Extracted from VoiceAccessibilityService to ensure reliable, isolated unit testability.
 */
object TextInsertion {

    data class Result(
        val text: String,
        val cursorPosition: Int,
        val normalizedSelStart: Int,
        val normalizedSelEnd: Int
    )

    private val KNOWN_PLACEHOLDER_REGEX = Regex(
        "^(?:" +
            // Chinese placeholders (常見通訊軟體與輸入框預設文字)
            "(?:請?(?:在此)?(?:輸入|傳送|發送|留下|回覆)(?:訊息|文字|內容|留言|評論|關鍵字|網址)?)" +
            "|(?:搜尋(?:或輸入網址)?)" +
            "|(?:留個言吧)" +
            // English placeholders
            "|(?:(?:type|send|write|enter)\\s+(?:a\\s+)?(?:message|text|comment))" +
            "|(?:type\\s+(?:something|here))" +
            "|(?:enter\\s+text(?:\\s+here)?)" +
            "|(?:search(?:\\s+or\\s+type\\s+(?:web\\s+)?url)?)" +
            "|(?:add\\s+a\\s+comment)" +
            "|(?:leave\\s+a\\s+comment)" +
            "|(?:reply)" +
        ")$",
        RegexOption.IGNORE_CASE
    )

    /**
     * Normalizes text by trimming whitespace, trailing ellipsis, colons, etc.
     */
    fun normalizeHint(text: CharSequence?): String {
        return text?.toString()
            ?.trim()
            ?.trimEnd('.', '…', ':', '：', ' ', '\t', '\n', '\r')
            ?.trim()
            ?: ""
    }

    /**
     * Determines whether [originalText] represents placeholder/hint text rather than actual user input.
     *
     * Considers:
     * 1. Explicit [isShowingHintText] flag from AccessibilityNodeInfo (Android 8.0+)
     * 2. Equivalence between [originalText] and [hintText] (ignoring whitespace and trailing ellipses/punctuation)
     * 3. Equivalence between [originalText] and [contentDescription]
     * 4. Known common placeholder texts (e.g. "輸入訊息", "請輸入訊息", "Type a message", etc.)
     */
    fun isHintText(
        originalText: CharSequence?,
        hintText: CharSequence? = null,
        isShowingHintText: Boolean = false,
        contentDescription: CharSequence? = null
    ): Boolean {
        if (originalText.isNullOrEmpty()) return false
        if (isShowingHintText) return true

        val origNorm = normalizeHint(originalText)
        if (origNorm.isEmpty()) return false

        // 1. Check against hintText
        if (!hintText.isNullOrBlank()) {
            val hintNorm = normalizeHint(hintText)
            if (origNorm.equals(hintNorm, ignoreCase = true)) {
                return true
            }
        }

        // 2. Check against contentDescription
        if (!contentDescription.isNullOrBlank()) {
            val descNorm = normalizeHint(contentDescription)
            if (origNorm.equals(descNorm, ignoreCase = true)) {
                return true
            }
        }

        // 3. Check against known placeholder patterns
        if (KNOWN_PLACEHOLDER_REGEX.matches(origNorm)) {
            return true
        }

        return false
    }

    /**
     * Inserts [insertedText] into [originalText] at the range specified by [rawSelStart] and [rawSelEnd].
     *
     * Handles:
     * - Null or empty original text
     * - Hint / placeholder text detection (treated as empty if detected)
     * - Negative selection indices (cursor uninitialized -> defaults to end of original text)
     * - Inverted selection bounds (start > end -> swapped)
     * - Clamping indices to `[0, originalText.length]`
     * - Range replacement when start != end (e.g. replacing highlighted/selected text)
     * - Calculating new cursor position right after the inserted text
     *
     * @param originalText The original text in the editable field (null treated as empty)
     * @param rawSelStart The reported selection start index
     * @param rawSelEnd The reported selection end index
     * @param insertedText The new text to insert (null treated as empty)
     * @param isHint Whether originalText is placeholder/hint text (treated as empty if true)
     * @param hintText Optional hint text reported by the accessibility node
     * @param contentDescription Optional content description reported by the accessibility node
     * @return [Result] containing the combined text, new cursor, and normalized selection bounds
     */
    fun insert(
        originalText: CharSequence?,
        rawSelStart: Int,
        rawSelEnd: Int,
        insertedText: CharSequence?,
        isHint: Boolean = false,
        hintText: CharSequence? = null,
        contentDescription: CharSequence? = null
    ): Result {
        val resolvedIsHint = isHint || isHintText(
            originalText = originalText,
            hintText = hintText,
            isShowingHintText = false,
            contentDescription = contentDescription
        )
        val orig = if (resolvedIsHint || originalText == null) "" else originalText.toString()
        val insert = insertedText?.toString() ?: ""
        val textLen = orig.length

        var selStart = rawSelStart
        var selEnd = rawSelEnd

        if (resolvedIsHint || selStart < 0 || selEnd < 0) {
            selStart = textLen
            selEnd = textLen
        } else {
            selStart = selStart.coerceIn(0, textLen)
            selEnd = selEnd.coerceIn(0, textLen)
            if (selStart > selEnd) {
                val tmp = selStart
                selStart = selEnd
                selEnd = tmp
            }
        }

        val combined = orig.substring(0, selStart) + insert + orig.substring(selEnd)
        val newCursor = selStart + insert.length

        return Result(
            text = combined,
            cursorPosition = newCursor,
            normalizedSelStart = selStart,
            normalizedSelEnd = selEnd
        )
    }
}
