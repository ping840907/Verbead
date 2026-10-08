package com.ping.verbead.util

import org.junit.Assert.assertEquals
import org.junit.Test

class TextInsertionTest {

    @Test
    fun testInsertAtStart() {
        val result = TextInsertion.insert(
            originalText = "World",
            rawSelStart = 0,
            rawSelEnd = 0,
            insertedText = "Hello "
        )
        assertEquals("Hello World", result.text)
        assertEquals(6, result.cursorPosition)
        assertEquals(0, result.normalizedSelStart)
        assertEquals(0, result.normalizedSelEnd)
    }

    @Test
    fun testInsertAtEnd() {
        val result = TextInsertion.insert(
            originalText = "Hello",
            rawSelStart = 5,
            rawSelEnd = 5,
            insertedText = " World"
        )
        assertEquals("Hello World", result.text)
        assertEquals(11, result.cursorPosition)
        assertEquals(5, result.normalizedSelStart)
        assertEquals(5, result.normalizedSelEnd)
    }

    @Test
    fun testInsertInMiddle() {
        val result = TextInsertion.insert(
            originalText = "Hello World",
            rawSelStart = 5,
            rawSelEnd = 5,
            insertedText = " Beautiful"
        )
        assertEquals("Hello Beautiful World", result.text)
        assertEquals(15, result.cursorPosition)
        assertEquals(5, result.normalizedSelStart)
        assertEquals(5, result.normalizedSelEnd)
    }

    @Test
    fun testReplaceSelection() {
        val result = TextInsertion.insert(
            originalText = "Hello Old World",
            rawSelStart = 6,
            rawSelEnd = 9,
            insertedText = "New"
        )
        assertEquals("Hello New World", result.text)
        assertEquals(9, result.cursorPosition)
        assertEquals(6, result.normalizedSelStart)
        assertEquals(9, result.normalizedSelEnd)
    }

    @Test
    fun testInvertedSelectionBounds() {
        val result = TextInsertion.insert(
            originalText = "Hello Old World",
            rawSelStart = 9,
            rawSelEnd = 6,
            insertedText = "New"
        )
        assertEquals("Hello New World", result.text)
        assertEquals(9, result.cursorPosition)
        assertEquals(6, result.normalizedSelStart)
        assertEquals(9, result.normalizedSelEnd)
    }

    @Test
    fun testEmptyOriginal() {
        val result = TextInsertion.insert(
            originalText = "",
            rawSelStart = 0,
            rawSelEnd = 0,
            insertedText = "Verbead"
        )
        assertEquals("Verbead", result.text)
        assertEquals(7, result.cursorPosition)
        assertEquals(0, result.normalizedSelStart)
        assertEquals(0, result.normalizedSelEnd)
    }

    @Test
    fun testNullOriginal() {
        val result = TextInsertion.insert(
            originalText = null,
            rawSelStart = 0,
            rawSelEnd = 0,
            insertedText = "Verbead"
        )
        assertEquals("Verbead", result.text)
        assertEquals(7, result.cursorPosition)
    }

    @Test
    fun testNegativeSelectionIndicesDefaultsToEnd() {
        val result = TextInsertion.insert(
            originalText = "Hello",
            rawSelStart = -1,
            rawSelEnd = -1,
            insertedText = " World"
        )
        assertEquals("Hello World", result.text)
        assertEquals(11, result.cursorPosition)
        assertEquals(5, result.normalizedSelStart)
        assertEquals(5, result.normalizedSelEnd)
    }

    @Test
    fun testSelectionExceedingLengthClamps() {
        val result = TextInsertion.insert(
            originalText = "Hello",
            rawSelStart = 10,
            rawSelEnd = 20,
            insertedText = "!"
        )
        assertEquals("Hello!", result.text)
        assertEquals(6, result.cursorPosition)
        assertEquals(5, result.normalizedSelStart)
        assertEquals(5, result.normalizedSelEnd)
    }

    @Test
    fun testHintTextFlagReplacesPlaceholder() {
        val result = TextInsertion.insert(
            originalText = "請在此輸入文字...",
            rawSelStart = 0,
            rawSelEnd = 0,
            insertedText = "實際輸入內容",
            isHint = true
        )
        assertEquals("實際輸入內容", result.text)
        assertEquals(6, result.cursorPosition)
        assertEquals(0, result.normalizedSelStart)
        assertEquals(0, result.normalizedSelEnd)
    }

    @Test
    fun testEmptyInsertedText() {
        val result = TextInsertion.insert(
            originalText = "Hello World",
            rawSelStart = 5,
            rawSelEnd = 5,
            insertedText = ""
        )
        assertEquals("Hello World", result.text)
        assertEquals(5, result.cursorPosition)
    }

    @Test
    fun testNullInsertedText() {
        val result = TextInsertion.insert(
            originalText = "Hello World",
            rawSelStart = 5,
            rawSelEnd = 5,
            insertedText = null
        )
        assertEquals("Hello World", result.text)
        assertEquals(5, result.cursorPosition)
    }

    @Test
    fun testTraditionalChineseInsertion() {
        val result = TextInsertion.insert(
            originalText = "今天天氣真好",
            rawSelStart = 2,
            rawSelEnd = 4,
            insertedText = "陽光明媚"
        )
        assertEquals("今天陽光明媚真好", result.text)
        assertEquals(6, result.cursorPosition)
    }

    @Test
    fun testInputMessagePlaceholderIsReplacedWhenHintFlagged() {
        // 當無障礙服務透過互動探測判定為 Placeholder 時（傳入 isHint = true），清空替換而非附加在後
        val result = TextInsertion.insert(
            originalText = "輸入訊息",
            rawSelStart = 0,
            rawSelEnd = 0,
            insertedText = "明天下午兩點見",
            isHint = true
        )
        assertEquals("明天下午兩點見", result.text)
        assertEquals(7, result.cursorPosition)
        assertEquals(0, result.normalizedSelStart)
        assertEquals(0, result.normalizedSelEnd)
    }

    @Test
    fun testHintTextMatchingOriginalTextReplaces() {
        val result = TextInsertion.insert(
            originalText = "請輸入自訂欄位名稱",
            rawSelStart = -1,
            rawSelEnd = -1,
            insertedText = "我的欄位",
            hintText = "請輸入自訂欄位名稱..."
        )
        assertEquals("我的欄位", result.text)
    }

    @Test
    fun testContentDescriptionMatchingOriginalTextReplaces() {
        val result = TextInsertion.insert(
            originalText = "搜尋",
            rawSelStart = -1,
            rawSelEnd = -1,
            insertedText = "搜尋關鍵字",
            contentDescription = "搜尋"
        )
        assertEquals("搜尋關鍵字", result.text)
    }

    @Test
    fun testRealUserInputPreservedWhenNotPlaceholder() {
        // 使用者先前已輸入真實內文，不應被誤判為 Placeholder
        val result1 = TextInsertion.insert(
            originalText = "輸入訊息明天再說",
            rawSelStart = 8,
            rawSelEnd = 8,
            insertedText = "喔"
        )
        assertEquals("輸入訊息明天再說喔", result1.text)

        val result2 = TextInsertion.insert(
            originalText = "請輸入密碼1234",
            rawSelStart = -1,
            rawSelEnd = -1,
            insertedText = "驗證"
        )
        assertEquals("請輸入密碼1234驗證", result2.text)
    }

    @Test
    fun testEvaluateProbeResultForPlaceholderReappearance() {
        // 模擬通訊軟體（如 LINE）輸入框在清空後提示字「輸入訊息」重新浮現
        val isPlaceholder = TextInsertion.evaluateProbeResult(
            initialText = "輸入訊息",
            textWithSpace = " ",
            textAfterClear = "輸入訊息",
            isHintAfterClear = false
        )
        org.junit.Assert.assertTrue(isPlaceholder)
    }

    @Test
    fun testEvaluateProbeResultForForeignLanguagePlaceholder() {
        // 模擬各國語言（日文/英文/韓文）在清空後提示文字重新浮現
        val isJaPlaceholder = TextInsertion.evaluateProbeResult(
            initialText = "メッセージを入力...",
            textWithSpace = " ",
            textAfterClear = "メッセージを入力",
            isHintAfterClear = false
        )
        org.junit.Assert.assertTrue(isJaPlaceholder)

        val isEnPlaceholder = TextInsertion.evaluateProbeResult(
            initialText = "Write a message…",
            textWithSpace = " ",
            textAfterClear = "Write a message",
            isHintAfterClear = false
        )
        org.junit.Assert.assertTrue(isEnPlaceholder)
    }

    @Test
    fun testEvaluateProbeResultForRealUserText() {
        // 真實使用者輸入內容在清空後消失（為空字串或變成通訊軟體灰色提示），絕不等於原本使用者文字
        val isRealText1 = TextInsertion.evaluateProbeResult(
            initialText = "明天下午兩點見",
            textWithSpace = " ",
            textAfterClear = "",
            isHintAfterClear = false
        )
        org.junit.Assert.assertFalse(isRealText1)

        val isRealText2 = TextInsertion.evaluateProbeResult(
            initialText = "明天下午兩點見",
            textWithSpace = " ",
            textAfterClear = "輸入訊息",
            isHintAfterClear = false
        )
        org.junit.Assert.assertFalse(isRealText2)
    }

    @Test
    fun testEvaluateProbeResultWithSystemHintFlag() {
        val isPlaceholder = TextInsertion.evaluateProbeResult(
            initialText = "Search",
            textWithSpace = " ",
            textAfterClear = "Search",
            isHintAfterClear = true
        )
        org.junit.Assert.assertTrue(isPlaceholder)
    }

    @Test
    fun testEvaluateSelectionProbeForHandTypedTextIdenticalToPlaceholder() {
        // 極端情況：佔位符為「輸入訊息」的欄位中，使用者手動打入「輸入訊息」
        // 由於真實字串緩衝區 mText 長度為 4，系統具備選取能力 (canSelect = true)，判定為真實內容而非佔位符！
        val isPlaceholder = TextInsertion.evaluateSelectionProbe(
            textLength = 4, // "輸入訊息".length
            canSelect = true
        )
        // 必須為 false（代表非佔位符，是使用者手打內容，保留不替換！）
        org.junit.Assert.assertEquals(false, isPlaceholder)
    }

    @Test
    fun testEvaluateSelectionProbeForEmptyFieldWithPlaceholder() {
        // 一般情況：欄位為空，但 AccessibilityNodeInfo 將佔位符「輸入訊息」回傳為 text
        // 由於真實字串緩衝區 mText 長度為 0，TextView.canSelectText() 為 false，系統拒絕選取動作 (canSelect = false)！
        val isPlaceholder = TextInsertion.evaluateSelectionProbe(
            textLength = 4, // "輸入訊息".length
            canSelect = false
        )
        // 必須為 true（代表底層空緩衝區的佔位提示，進行清空替換！）
        org.junit.Assert.assertEquals(true, isPlaceholder)
    }

    @Test
    fun testEvaluateSelectionProbeForGeneralText() {
        val isReal = TextInsertion.evaluateSelectionProbe(
            textLength = 10,
            canSelect = true
        )
        org.junit.Assert.assertEquals(false, isReal)

        val isPlaceholder = TextInsertion.evaluateSelectionProbe(
            textLength = 10,
            canSelect = false
        )
        org.junit.Assert.assertEquals(true, isPlaceholder)
    }
}
