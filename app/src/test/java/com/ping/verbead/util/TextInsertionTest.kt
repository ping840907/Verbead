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
    fun testInputMessagePlaceholderIsReplacedWithoutPrepending() {
        // 模擬通訊軟體（如 LINE）預設文字「輸入訊息」未標記為 Hint 時，自動識別為 Placeholder 並清空替換
        val result = TextInsertion.insert(
            originalText = "輸入訊息",
            rawSelStart = 4,
            rawSelEnd = 4,
            insertedText = "明天下午兩點見",
            isHint = false
        )
        assertEquals("明天下午兩點見", result.text)
        assertEquals(7, result.cursorPosition)
        assertEquals(0, result.normalizedSelStart)
        assertEquals(0, result.normalizedSelEnd)
    }

    @Test
    fun testInputMessageWithEllipsisIsReplaced() {
        val resultDot = TextInsertion.insert(
            originalText = "輸入訊息...",
            rawSelStart = -1,
            rawSelEnd = -1,
            insertedText = "語音識別結果"
        )
        assertEquals("語音識別結果", resultDot.text)

        val resultEllipsis = TextInsertion.insert(
            originalText = "輸入訊息…",
            rawSelStart = 0,
            rawSelEnd = 0,
            insertedText = "OCR文字"
        )
        assertEquals("OCR文字", resultEllipsis.text)
    }

    @Test
    fun testInputTextPlaceholderIsReplaced() {
        val result = TextInsertion.insert(
            originalText = "輸入文字",
            rawSelStart = -1,
            rawSelEnd = -1,
            insertedText = "條碼結果"
        )
        assertEquals("條碼結果", result.text)
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
    fun testEnglishPlaceholderIsReplaced() {
        val result = TextInsertion.insert(
            originalText = "Type a message...",
            rawSelStart = -1,
            rawSelEnd = -1,
            insertedText = "Hello World"
        )
        assertEquals("Hello World", result.text)
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
}
