package com.ping.verbead.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LineSplitterTest {

    /** Characters 40 wide and 40 high, laid out left to right from ([x], [y]); a space has no box. */
    private fun boxes(text: String, x: Int, y: Int): List<CharBox?> =
        text.mapIndexed { i, c -> if (c == ' ' || c == '\n') null else CharBox(x + i * 40, y, x + (i + 1) * 40, y + 40) }

    @Test
    fun aWrappedTextIsCutIntoItsLines() {
        // "今天天氣很好" on the first line, "我們去公園" on the second
        val text = "今天天氣很好我們去公園"
        val b = boxes("今天天氣很好", 0, 0) + boxes("我們去公園", 0, 60)
        val lines = LineSplitter.split(text, b)!!
        assertEquals(listOf("今天天氣很好", "我們去公園"), lines.map { it.text })
        assertEquals(listOf(0, 60), lines.map { it.top })
        assertEquals(listOf(240, 200), lines.map { it.right })
    }

    @Test
    fun theFirstLineMayStartFurtherRight() {
        // a link sits at the start of the first line, so the wrapped text begins after it
        val text = "接在連結後面的文字繼續到下一行"
        val b = boxes("接在連結後面的文字", 300, 0) + boxes("繼續到下一行", 0, 60)
        val lines = LineSplitter.split(text, b)!!
        assertEquals(300, lines[0].left)
        assertEquals(0, lines[1].left)
    }

    @Test
    fun spacesAndLineBreaksInsideALineStay() {
        val text = "hello world and more"
        val b = boxes("hello world", 0, 0) + listOf(null) + boxes("and more", 0, 60)
        val lines = LineSplitter.split(text, b)!!
        assertEquals(listOf("hello world", "and more"), lines.map { it.text })
    }

    @Test
    fun oneLineIsNothingToCut() {
        assertNull(LineSplitter.split("只有一行文字", boxes("只有一行文字", 0, 0)))
    }

    @Test
    fun noBoxesMeansNoAnswer() {
        assertNull(LineSplitter.split("沒有任何位置資訊", emptyList()))
        assertNull(LineSplitter.split("沒有任何位置資訊", List(8) { null }))
    }

    @Test
    fun aSmallRiseInOneCharacterDoesNotStartALine() {
        // a superscript or a taller glyph sticks out of its line by a few pixels
        val b = boxes("今天天氣很好", 0, 0).toMutableList()
        b[2] = CharBox(80, -10, 120, 38)
        assertNull(LineSplitter.split("今天天氣很好", b))
    }

    @Test
    fun aNodeTooLongForOneLineLooksWrapped() {
        // 30 Chinese characters in a box 1000 wide and 130 high (two lines of 65)
        assertTrue(LineSplitter.looksMultiLine("字".repeat(30), 1000, 130))
        // 30 Latin characters in a box 600 wide and 130 high
        assertTrue(LineSplitter.looksMultiLine("a".repeat(60), 1000, 80))
    }

    @Test
    fun aNodeThatFitsOneLineDoesNotLookWrapped() {
        assertFalse(LineSplitter.looksMultiLine("字".repeat(12), 780, 65))
        assertFalse(LineSplitter.looksMultiLine("a".repeat(30), 600, 40))
        assertFalse(LineSplitter.looksMultiLine("短", 60, 60)) // too short to ask about
        assertFalse(LineSplitter.looksMultiLine("字".repeat(30), 0, 60))
    }
}
