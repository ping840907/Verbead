package com.ping.verbead.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingOrderTest {

    private fun item(l: Int, t: Int, r: Int, b: Int, text: String) = ScreenTextItem(l, t, r, b, text)

    @Test
    fun linesTopToBottomAndLeftToRight() {
        val items = listOf(
            item(500, 100, 700, 140, "time"),
            item(0, 300, 400, 340, "second"),
            item(0, 100, 400, 140, "name"),
        )
        assertEquals("name time\nsecond", ReadingOrder.toText(items))
    }

    @Test
    fun slightlyMisalignedItemsShareALine() {
        val items = listOf(
            item(300, 112, 500, 152, "B"),
            item(0, 100, 200, 140, "A"),
        )
        assertEquals(1, ReadingOrder.sort(items).size)
        assertEquals("A B", ReadingOrder.toText(items))
    }

    @Test
    fun itemsBelowEachOtherAreSeparateLines() {
        val items = listOf(item(0, 100, 200, 140, "A"), item(0, 141, 200, 181, "B"))
        assertEquals("A\nB", ReadingOrder.toText(items))
    }

    @Test
    fun emptyInputGivesEmpty() {
        assertTrue(ReadingOrder.sort(emptyList()).isEmpty())
        assertEquals("", ReadingOrder.toText(emptyList()))
    }

    @Test
    fun linesCarryTheirBoundingBox() {
        val lines = ReadingOrder.toLines(
            listOf(item(300, 112, 500, 152, "B"), item(0, 100, 200, 140, "A"), item(0, 300, 400, 340, "C"))
        )
        assertEquals(2, lines.size)
        assertEquals(ScreenLine(0, 100, 500, 152, "A B", gap = 100), lines[0])
        assertEquals(ScreenLine(0, 300, 400, 340, "C"), lines[1])
    }

    @Test
    fun textIsTrimmed() {
        assertEquals("a b", ReadingOrder.toText(listOf(item(0, 0, 10, 10, " a "), item(20, 0, 30, 10, "b "))))
    }

    @Test
    fun touchingChineseBoxesAreOneTextAndColumnsKeepASpace() {
        // a recognizer that cut one line into two boxes: no space inserted between Chinese pieces that touch
        val cut = ReadingOrder.toLines(listOf(item(0, 0, 300, 40, "選擇朗讀用的聲音，"), item(305, 0, 600, 40, "下載的聲音更自然")))
        assertEquals("選擇朗讀用的聲音，下載的聲音更自然", cut.single().text)
        assertEquals(5, cut.single().gap)
        // a label and its status, far apart: still two things
        val row = ReadingOrder.toLines(listOf(item(0, 0, 300, 40, "麥克風錄音權限"), item(900, 0, 1000, 40, "已就緒")))
        assertEquals("麥克風錄音權限 已就緒", row.single().text)
        assertEquals(600, row.single().gap)
        // English words always keep their space
        val words = ReadingOrder.toLines(listOf(item(0, 0, 100, 40, "hello"), item(105, 0, 200, 40, "world")))
        assertEquals("hello world", words.single().text)
    }
}
