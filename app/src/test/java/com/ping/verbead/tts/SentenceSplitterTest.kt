package com.ping.verbead.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SentenceSplitterTest {

    private fun texts(s: String, maxLen: Int = 80) = SentenceSplitter.split(s, maxLen).map { it.text }

    @Test
    fun splitsChineseAtFullWidthTerminators() {
        assertEquals(listOf("今天天氣很好。", "我們去公園吧！", "好嗎？"), texts("今天天氣很好。我們去公園吧！好嗎？"))
    }

    @Test
    fun splitsEnglishAtPeriodsFollowedBySpace() {
        assertEquals(listOf("Hello there.", "How are you?", "Fine!"), texts("Hello there. How are you? Fine!"))
    }

    @Test
    fun keepsDecimalsAndDottedTokensWhole() {
        assertEquals(listOf("Pi is 3.14 today."), texts("Pi is 3.14 today."))
        assertEquals(listOf("see file.txt now"), texts("see file.txt now"))
    }

    @Test
    fun lineBreaksSeparateSentences() {
        assertEquals(listOf("第一行", "第二行"), texts("第一行\n第二行"))
    }

    @Test
    fun closingQuoteStaysWithItsSentence() {
        assertEquals(listOf("他說：「好。」", "然後走了。"), texts("他說：「好。」然後走了。"))
    }

    @Test
    fun offsetsPointBackIntoTheSourceText() {
        val src = "  甲乙。\n\n丙丁！ "
        for (s in SentenceSplitter.split(src)) assertEquals(s.text, src.substring(s.start, s.end))
    }

    @Test
    fun punctuationOnlyPiecesAreDropped() {
        // trailing punctuation stays with its sentence instead of becoming a silent piece of its own
        assertEquals(listOf("你好。……!!"), texts("你好。……!!"))
        assertEquals(listOf("你好。", "再見。"), texts("你好。\n……\n再見。"))
        assertTrue(texts("。……!!").isEmpty())
        assertTrue(texts("   \n \n").isEmpty())
        assertTrue(texts("").isEmpty())
    }

    @Test
    fun longPieceIsCutAtACommaNearTheLimit() {
        val long = "一二三四五六七八九十，甲乙丙丁戊己庚辛壬癸，子丑寅卯辰巳午未申酉戌亥"
        val parts = texts(long, maxLen = 12)
        assertTrue(parts.all { it.length <= 12 })
        assertEquals(long, parts.joinToString(""))
        assertEquals("一二三四五六七八九十，", parts.first())
    }

    @Test
    fun longPieceWithoutBreaksIsCutAtTheLimit() {
        val parts = texts("a".repeat(30), maxLen = 10)
        assertEquals(listOf(10, 10, 10), parts.map { it.length })
    }

    @Test
    fun hardCutNeverSplitsASurrogatePair() {
        val src = "a".repeat(9) + "😀" + "b".repeat(9) // the emoji straddles the 10-char limit
        for (p in texts(src, maxLen = 10)) {
            assertTrue(!p.endsWith("\uD83D"))
            assertTrue(!p.startsWith("\uDE00"))
        }
    }
}
