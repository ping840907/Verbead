package com.ping.verbead.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingPlanTest {

    /** A text line 30 high and 400 wide at [top]. */
    private fun line(top: Int, text: String, left: Int = 0, right: Int = 400) = ScreenLine(left, top, right, top + 30, text)

    @Test
    fun everySentenceIsTiedToTheLineItIsOn() {
        // far apart: three separate blocks, not one wrapped paragraph
        val plan = ReadingPlan.fromLines(
            listOf(
                line(0, "第一行。第一行的第二句。"),
                line(200, "第二行沒有句號"),
                line(400, "第三行。"),
            )
        )
        assertEquals(listOf("第一行。", "第一行的第二句。", "第二行沒有句號", "第三行。"), plan.sentences.map { it.text })
        assertEquals(listOf(0, 0, 1, 2), plan.sentences.indices.map { plan.lineIndexOf(it) })
        assertEquals(0, plan.firstSentenceOfLine(0))
        assertEquals(2, plan.firstSentenceOfLine(1))
        assertEquals(3, plan.firstSentenceOfLine(2))
        assertEquals("第三行。", plan.lineOf(3)!!.text)
        assertEquals(400, plan.lineOf(3)!!.top)
    }

    @Test
    fun aWrappedParagraphIsReadAsItsSentencesNotOneLineAtATime() {
        // one sentence that the screen broke over two lines, and a second sentence that starts on the second line
        val plan = ReadingPlan.fromLines(
            listOf(
                line(0, "這是一個很長的句子，要折到下一行"),
                line(40, "才會結束。接著是第二句。"),
            )
        )
        assertEquals(listOf("這是一個很長的句子，要折到下一行才會結束。", "接著是第二句。"), plan.sentences.map { it.text })
        // the first sentence covers both lines, the second only the last
        assertEquals(listOf(0, 1), plan.linesOf(0).map { it.top / 40 })
        assertEquals(listOf(1), plan.linesOf(1).map { it.top / 40 })
        // a tap on either line of the first sentence starts it
        assertEquals(0, plan.firstSentenceOfLine(0))
        assertEquals(0, plan.firstSentenceOfLine(1))
    }

    @Test
    fun englishLinesAreJoinedWithASpaceAndASplitWordIsMended() {
        val plan = ReadingPlan.fromLines(
            listOf(
                line(0, "The quick brown fox jumps over"),
                line(40, "the lazy dog near the for-"),
                line(80, "est path."),
            )
        )
        assertEquals(listOf("The quick brown fox jumps over the lazy dog near the forest path."), plan.sentences.map { it.text })
        assertEquals(3, plan.linesOf(0).size)
    }

    @Test
    fun separateBlocksAreNeverJoined() {
        val plan = ReadingPlan.fromLines(
            listOf(
                line(0, "第一段落沒有結尾標點"),
                line(40, "第一段落的第二行。"),
                line(200, "第二段落開始了。"),
            )
        )
        assertEquals(2, plan.sentences.size)
        assertEquals("第二段落開始了。", plan.sentences[1].text)
    }

    @Test
    fun linesThatCleanToNothingAreDroppedAndLaterLinesKeepTheirOwnRect() {
        val plan = ReadingPlan.fromLines(
            listOf(
                line(0, "開始。"),
                line(200, "😀🎉"),                 // only emoji
                line(400, "https://example.com"), // only a link
                line(600, "結束。"),
            )
        )
        assertEquals(listOf("開始。", "結束。"), plan.sentences.map { it.text })
        assertEquals(2, plan.lines.size)
        assertEquals(600, plan.lineOf(1)!!.top)
    }

    @Test
    fun aLineWithSeveralSentencesStartsAtItsFirst() {
        val plan = ReadingPlan.fromLines(listOf(line(0, "甲。"), line(200, "乙。丙。丁。")))
        assertEquals(1, plan.firstSentenceOfLine(1))
        assertNull(plan.firstSentenceOfLine(5))
    }

    @Test
    fun textFromASelectionHasNoPositions() {
        val plan = ReadingPlan.fromText("你好。再見。")
        assertEquals(2, plan.sentences.size)
        assertFalse(plan.hasPositions)
        assertNull(plan.lineIndexOf(0))
        assertNull(plan.lineOf(1))
        assertTrue(plan.linesOf(0).isEmpty())
    }

    @Test
    fun nothingToReadIsEmpty() {
        assertTrue(ReadingPlan.EMPTY.isEmpty)
        assertTrue(ReadingPlan.fromLines(emptyList()).isEmpty)
        assertTrue(ReadingPlan.fromLines(listOf(line(0, "   "))).isEmpty)
        assertTrue(ReadingPlan.fromText("😀").isEmpty)
        assertNull(ReadingPlan.EMPTY.lineIndexOf(0))
    }

    @Test
    fun sentenceOffsetsPointIntoThePlanText() {
        val plan = ReadingPlan.fromLines(listOf(line(0, "甲乙丙丁戊己庚辛"), line(40, "壬癸。子丑。"), line(300, "寅卯。")))
        for (s in plan.sentences) assertEquals(s.text, plan.text.substring(s.start, s.end))
    }
}
