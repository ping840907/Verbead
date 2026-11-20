package com.ping.verbead.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingPlanTest {

    private fun line(top: Int, text: String, left: Int = 0, right: Int = 100) = ScreenLine(left, top, right, top + 30, text)

    @Test
    fun everySentenceIsTiedToTheLineItIsOn() {
        val plan = ReadingPlan.fromLines(
            listOf(
                line(0, "第一行。第一行的第二句。"),
                line(40, "第二行沒有句號"),
                line(80, "第三行。"),
            )
        )
        assertEquals(listOf("第一行。", "第一行的第二句。", "第二行沒有句號", "第三行。"), plan.sentences.map { it.text })
        assertEquals(listOf(0, 0, 1, 2), plan.sentences.indices.map { plan.lineIndexOf(it) })
        assertEquals(0, plan.firstSentenceOfLine(0))
        assertEquals(2, plan.firstSentenceOfLine(1))
        assertEquals(3, plan.firstSentenceOfLine(2))
        assertEquals("第三行。", plan.lineOf(3)!!.text)
        assertEquals(80, plan.lineOf(3)!!.top)
    }

    @Test
    fun aWrappedParagraphReadsAsOneSentencePerLine() {
        // the screen breaks a sentence over two lines; each line is its own sentence, never one that spans both
        val plan = ReadingPlan.fromLines(listOf(line(0, "這是一個很長的句子，"), line(40, "在這裡才結束。")))
        assertEquals(2, plan.sentences.size)
        assertEquals(0, plan.lineIndexOf(0))
        assertEquals(1, plan.lineIndexOf(1))
    }

    @Test
    fun linesThatCleanToNothingAreDroppedAndLaterLinesKeepTheirOwnRect() {
        val plan = ReadingPlan.fromLines(
            listOf(
                line(0, "開始。"),
                line(40, "😀🎉"),                 // only emoji
                line(80, "https://example.com"), // only a link
                line(120, "結束。"),
            )
        )
        assertEquals(listOf("開始。", "結束。"), plan.sentences.map { it.text })
        assertEquals(2, plan.lines.size)
        assertEquals(120, plan.lineOf(1)!!.top)
    }

    @Test
    fun aLineWithSeveralSentencesStartsAtItsFirst() {
        val plan = ReadingPlan.fromLines(listOf(line(0, "甲。"), line(40, "乙。丙。丁。")))
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
        val plan = ReadingPlan.fromLines(listOf(line(0, "甲乙。"), line(40, "丙丁。")))
        for (s in plan.sentences) assertEquals(s.text, plan.text.substring(s.start, s.end))
    }
}
