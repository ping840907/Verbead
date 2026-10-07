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
     * Inserts [insertedText] into [originalText] at the range specified by [rawSelStart] and [rawSelEnd].
     *
     * Handles:
     * - Null or empty original text
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
     * @return [Result] containing the combined text, new cursor, and normalized selection bounds
     */
    fun insert(
        originalText: CharSequence?,
        rawSelStart: Int,
        rawSelEnd: Int,
        insertedText: CharSequence?,
        isHint: Boolean = false
    ): Result {
        val orig = if (isHint || originalText == null) "" else originalText.toString()
        val insert = insertedText?.toString() ?: ""
        val textLen = orig.length

        var selStart = rawSelStart
        var selEnd = rawSelEnd

        if (isHint || selStart < 0 || selEnd < 0) {
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
