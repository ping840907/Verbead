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

        return false
    }

    /**
     * Evaluates probe results to determine if [initialText] is a dynamic placeholder.
     *
     * Principles:
     * - An empty field displaying a placeholder (hint) will dynamically revert to the placeholder
     *   whenever cleared to empty ("").
     * - In contrast, real user text will vanish when cleared and will never reappear in an empty field.
     * - When a space is entered, a placeholder is displaced; when cleared, it returns.
     *
     * @param initialText Text captured before probing
     * @param textWithSpace Text returned after temporarily writing a space " "
     * @param textAfterClear Text returned after clearing the field to ""
     * @param isHintAfterClear Whether the system explicitly flagged isShowingHintText after clearing
     * @return true if the probe proves [initialText] is a placeholder, false otherwise
     */
    fun evaluateProbeResult(
        initialText: CharSequence?,
        textWithSpace: CharSequence?,
        textAfterClear: CharSequence?,
        isHintAfterClear: Boolean = false
    ): Boolean {
        if (initialText.isNullOrEmpty()) return false
        if (isHintAfterClear) return true

        val initNorm = normalizeHint(initialText)
        if (initNorm.isEmpty()) return false

        val clearNorm = normalizeHint(textAfterClear)
        // If the placeholder reappears upon clearing the field to empty, it is definitively a placeholder
        if (initNorm.equals(clearNorm, ignoreCase = true)) {
            return true
        }

        return false
    }

    /**
     * Evaluates selection probe results to determine if a text node represents a placeholder.
     *
     * Principles:
     * - In Android TextView architecture (canSelectText()), ACTION_SET_SELECTION only succeeds
     *   if the underlying text buffer contains characters (mText.length() > 0).
     * - An empty field displaying placeholder text will reject ACTION_SET_SELECTION (returns false).
     * - A field containing hand-typed text (even if identical to the placeholder) will accept
     *   ACTION_SET_SELECTION (returns true).
     *
     * @param textLength The length of the text reported by target.text
     * @param canSelect Whether target.performAction(ACTION_SET_SELECTION) succeeded
     * @return true if the node is an empty placeholder, false if it contains real user text
     */
    fun evaluateSelectionProbe(textLength: Int, canSelect: Boolean): Boolean {
        if (textLength <= 0) return false
        // If the view rejects selection, it has an empty buffer (placeholder)
        if (!canSelect) return true
        // If the view accepts selection, it has real characters in its buffer
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
