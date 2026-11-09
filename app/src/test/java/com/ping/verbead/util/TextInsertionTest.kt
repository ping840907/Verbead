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
    fun testHandTypedTextIdenticalToPlaceholderPreservedWhenCursorAtZero() {
        // 當使用者輸入與佔位符完全相同的文字（例如「輸入訊息」），且游標在 0 時
        // 經由選取探測判定非佔位符（isHint = false），插入前綴時原有手打文字必須被保留
        val result = TextInsertion.insert(
            originalText = "輸入訊息",
            rawSelStart = 0,
            rawSelEnd = 0,
            insertedText = "前綴：",
            isHint = false
        )
        assertEquals("前綴：輸入訊息", result.text)
        assertEquals(3, result.cursorPosition)
    }
}
