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
    fun textIsTrimmed() {
        assertEquals("a b", ReadingOrder.toText(listOf(item(0, 0, 10, 10, " a "), item(20, 0, 30, 10, "b "))))
    }
}
