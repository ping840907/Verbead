package com.ping.verbead.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParagraphsTest {

    /** A line 30 high at [top], [left] to [right] wide (default 400 wide). */
    private fun line(top: Int, text: String, left: Int = 0, right: Int = 400, gap: Int = 0) =
        ScreenLine(left, top, right, top + 30, text, gap)

    private fun groups(vararg lines: ScreenLine) = Paragraphs.group(lines.toList())

    @Test
    fun aParagraphWrappedOverThreeLinesIsOneGroup() {
        val g = groups(line(0, "第一行沒有結束"), line(40, "第二行也沒有結束"), line(80, "第三行結束了。"))
        assertEquals(listOf(listOf(0, 1, 2)), g)
    }

    @Test
    fun aLineThatEndsASentenceEndsTheParagraph() {
        for (end in listOf("。", "！", "？", ".", "!", "?", "；", "：", "…")) {
            val g = groups(line(0, "一行文字$end"), line(40, "下一行"))
            assertEquals("after $end", listOf(listOf(0), listOf(1)), g)
        }
    }

    @Test
    fun aLineEndingInThreeDotsMayCarryOn() {
        assertEquals(listOf(listOf(0, 1)), groups(line(0, "and then..."), line(40, "it went on")))
    }

    @Test
    fun aShortLineIsAHeadingOrTheEndOfAParagraphNotItsMiddle() {
        // the first line is much shorter than the one below: a heading
        val g = groups(line(0, "標題", right = 120), line(40, "內文內文內文內文內文內文內文內文內文"))
        assertEquals(listOf(listOf(0), listOf(1)), g)
    }

    @Test
    fun aLastShortLineStillJoinsItsParagraph() {
        val g = groups(line(0, "一整行的內容還沒結束"), line(40, "最後短短一行", right = 150))
        assertEquals(listOf(listOf(0, 1)), g)
    }

    @Test
    fun rowsOfAListAreNotJoined() {
        // each row is a label and a status, far apart, with a normal gap between rows
        val g = groups(
            line(0, "麥克風錄音權限 已就緒", gap = 500),
            line(45, "相機權限 已就緒", gap = 500),
            line(90, "懸浮窗顯示權限 已就緒", gap = 500),
        )
        assertEquals(listOf(listOf(0), listOf(1), listOf(2)), g)
    }

    @Test
    fun aTextLineThatTheRecognizerCutIntoTouchingBoxesStillWraps() {
        // two boxes side by side with next to no space between them are one line of text
        val g = groups(line(0, "選擇朗讀用的聲音。系統語音立即可用；下載的聲音更自然，且完全在", gap = 6), line(40, "手機上運作。"))
        assertEquals(listOf(listOf(0, 1)), g.map { it.take(2) })
        val g2 = groups(line(0, "上一行沒有結尾還在繼續說下去", gap = 6), line(40, "手機上運作"))
        assertEquals(listOf(listOf(0, 1)), g2)
    }

    @Test
    fun aGapBiggerThanLineSpacingStartsANewParagraph() {
        assertEquals(listOf(listOf(0), listOf(1)), groups(line(0, "上一段沒有結尾"), line(100, "下一段開始")))
    }

    @Test
    fun linesOverlappingEachOtherAreNotAWrap() {
        assertEquals(listOf(listOf(0), listOf(1)), groups(line(0, "同一行被讀了兩次"), line(5, "同一行被讀了兩次")))
    }

    @Test
    fun aDifferentSizeIsAHeadingNotACarryOn() {
        val big = ScreenLine(0, 40, 400, 100, "大字標題") // twice as tall
        assertEquals(listOf(listOf(0), listOf(1)), Paragraphs.group(listOf(line(0, "小字內文沒有結束"), big)))
    }

    @Test
    fun aDifferentLeftEdgeIsAnotherColumnOrBlock() {
        assertEquals(listOf(listOf(0), listOf(1)), groups(line(0, "左邊的欄位沒有結束"), line(40, "右邊的欄位", left = 200)))
    }

    @Test
    fun aFirstLineIndentIsAllowed() {
        assertEquals(listOf(listOf(0, 1)), groups(line(0, "段落首行縮排了兩個字", left = 50), line(40, "第二行靠左對齊")))
    }

    @Test
    fun aNarrowColumnOfShortLinesIsNotProse() {
        // 100 wide is under five line heights
        assertEquals(listOf(listOf(0), listOf(1)), groups(line(0, "一二三", right = 100), line(40, "四五六", right = 100)))
    }

    @Test
    fun everyLineIsInExactlyOneGroup() {
        val lines = listOf(line(0, "甲"), line(40, "乙"), line(300, "丙"), line(340, "丁。"), line(600, "戊"))
        val flat = Paragraphs.group(lines).flatten()
        assertEquals(lines.indices.toList(), flat)
    }

    @Test
    fun noLinesNoGroups() = assertTrue(Paragraphs.group(emptyList()).isEmpty())

    // ── gluing ──────────────────────────────────────────────────────────

    @Test
    fun chineseIsJoinedWithNothingBetween() {
        val g = Paragraphs.glue("今天天氣很好，", "我們去公園")
        assertEquals("", g.separator)
        assertEquals(0, g.dropFromPrev)
    }

    @Test
    fun mixedChineseAndLatinAtTheJoinHasNoSpaceEither() {
        assertEquals("", Paragraphs.glue("請把 GPU", "驅動程式更新").separator)
        assertEquals("", Paragraphs.glue("更新到", "version 2").separator)
    }

    @Test
    fun englishWordsAreJoinedWithASpace() {
        assertEquals(" ", Paragraphs.glue("the quick brown", "fox jumps").separator)
    }

    @Test
    fun aHyphenThatSplitAWordIsRemoved() {
        val g = Paragraphs.glue("into the for-", "est where")
        assertEquals("", g.separator)
        assertEquals(1, g.dropFromPrev)
    }

    @Test
    fun aHyphenInACompoundBeforeACapitalIsKept() {
        val g = Paragraphs.glue("a Jean-", "Paul said")
        assertEquals(" ", g.separator)
        assertEquals(0, g.dropFromPrev)
    }

    @Test
    fun emptyPiecesAddNothing() {
        assertEquals("", Paragraphs.glue("", "abc").separator)
        assertFalse(Paragraphs.glue("abc", "").separator.isNotEmpty())
    }
}
