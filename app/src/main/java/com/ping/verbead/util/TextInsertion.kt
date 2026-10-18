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
     * Represents the extracted text and selection state of the currently focused editable field.
     */
    data class EditableTextContext(
        val fullText: String,
        val selectedText: String?,
        val selectionStart: Int,
        val selectionEnd: Int
    ) {
        val hasSelection: Boolean
            get() = !selectedText.isNullOrEmpty()

        /**
         * The target text to be processed by SLM:
         * - If the user highlighted a specific substring, only that selection is targeted.
         * - Otherwise, the entire text in the editable field is targeted.
         */
        val targetText: String
            get() = if (hasSelection) selectedText!! else fullText

        val isEmpty: Boolean
            get() = targetText.isBlank()
    }

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

    /**
     * Extracts the current editable field's text and selection context, filtering out placeholder/hint text.
     */
    fun extractContext(
        originalText: CharSequence?,
        rawSelStart: Int,
        rawSelEnd: Int,
        isHint: Boolean = false,
        hintText: CharSequence? = null,
        contentDescription: CharSequence? = null
    ): EditableTextContext {
        val resolvedIsHint = isHint || isHintText(
            originalText = originalText,
            hintText = hintText,
            isShowingHintText = false,
            contentDescription = contentDescription
        )
        if (resolvedIsHint || originalText.isNullOrEmpty()) {
            return EditableTextContext(
                fullText = "",
                selectedText = null,
                selectionStart = 0,
                selectionEnd = 0
            )
        }
        val orig = originalText.toString()
        val textLen = orig.length
        var selStart = rawSelStart
        var selEnd = rawSelEnd
        if (selStart < 0 || selEnd < 0) {
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
        val selected = if (selEnd > selStart) orig.substring(selStart, selEnd) else null
        return EditableTextContext(
            fullText = orig,
            selectedText = selected,
            selectionStart = selStart,
            selectionEnd = selEnd
        )
    }

    /**
     * Applies an SLM-transformed text result to the editable field:
     * - If the user had highlighted a specific selection (selStart < selEnd), replaces only that selection.
     * - If the field had existing text without a highlight selection, replaces the entire field text
     *   (since the entire field text was passed to SLM as <source_text>), while preserving the original
     *   selection bounds so Undo (restoreLastSnapshot) restores the exact pre-SLM text and cursor.
     * - If the field was empty (or placeholder), inserts the SLM result into the empty field.
     */
    fun replaceForSlm(
        originalText: CharSequence?,
        rawSelStart: Int,
        rawSelEnd: Int,
        slmOutput: CharSequence?,
        isHint: Boolean = false,
        hintText: CharSequence? = null,
        contentDescription: CharSequence? = null
    ): Result {
        val ctx = extractContext(
            originalText = originalText,
            rawSelStart = rawSelStart,
            rawSelEnd = rawSelEnd,
            isHint = isHint,
            hintText = hintText,
            contentDescription = contentDescription
        )
        val output = slmOutput?.toString() ?: ""
        if (ctx.fullText.isEmpty()) {
            return Result(
                text = output,
                cursorPosition = output.length,
                normalizedSelStart = 0,
                normalizedSelEnd = 0
            )
        }
        return if (ctx.hasSelection) {
            val combined = ctx.fullText.substring(0, ctx.selectionStart) +
                output +
                ctx.fullText.substring(ctx.selectionEnd)
            val newCursor = ctx.selectionStart + output.length
            Result(
                text = combined,
                cursorPosition = newCursor,
                normalizedSelStart = ctx.selectionStart,
                normalizedSelEnd = ctx.selectionEnd
            )
        } else {
            // Whole field text was transformed by SLM -> replace whole field text
            Result(
                text = output,
                cursorPosition = output.length,
                normalizedSelStart = ctx.selectionStart,
                normalizedSelEnd = ctx.selectionEnd
            )
        }
    }

    /**
     * Determines whether an accessibility node's metadata identifies it as a native Undo button.
     * Strictly excludes:
     * - The service's own package
     * - The service's floating UI elements (bubble, x button, etc.)
     * - Redo buttons (e.g. "重做", "redo", "取消復原")
     */
    fun isUndoCandidate(
        nodePackageName: CharSequence?,
        servicePackageName: String,
        viewId: CharSequence?,
        contentDescription: CharSequence?,
        text: CharSequence?
    ): Boolean {
        if (nodePackageName != null && nodePackageName == servicePackageName) {
            return false
        }

        val vId = viewId?.toString()?.lowercase() ?: ""
        if (vId.contains("bubble_x") || vId.contains("verbead")) {
            return false
        }

        val desc = contentDescription?.toString()?.trim() ?: ""
        val txt = text?.toString()?.trim() ?: ""

        if (desc.isEmpty() && txt.isEmpty() && vId.isEmpty()) {
            return false
        }

        // Redo keywords to explicitly exclude
        val redoKeywords = listOf("redo", "重做", "取消復原", "取消撤销", "取消撤銷")
        if (redoKeywords.any { desc.contains(it, ignoreCase = true) || txt.contains(it, ignoreCase = true) || vId.contains(it) }) {
            return false
        }

        // Undo keywords in various languages
        val undoKeywords = listOf("復原", "撤銷", "撤销", "元に戻す", "실행취소", "실행 취소", "deshacer")
        if (undoKeywords.any { desc.contains(it, ignoreCase = true) || txt.contains(it, ignoreCase = true) }) {
            return true
        }

        if (desc.equals("undo", ignoreCase = true) ||
            desc.startsWith("undo ", ignoreCase = true) ||
            desc.endsWith(" undo", ignoreCase = true) ||
            txt.equals("undo", ignoreCase = true) ||
            vId.contains("undo")
        ) {
            return true
        }

        return false
    }
}
