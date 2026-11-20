package com.ping.verbead.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageContinuationTest {

    private fun s(vararg t: String) = t.mapIndexed { i, x -> Sentence(x, i * 100, i * 100 + x.length) }

    private val page1 = listOf("第一個句子寫在這裡。", "第二個句子也在這裡。", "第三個句子在最底下。")

    @Test
    fun aPageWithNothingInCommonStartsAtTheTop() {
        assertEquals(0, PageContinuation.firstNew(page1, s("全新的一頁從這裡開始。", "後面還有更多內容。")))
    }

    @Test
    fun repeatedSentencesAtTheTopAreSkipped() {
        val next = s("第二個句子也在這裡。", "第三個句子在最底下。", "新的內容從這句開始。", "再來是更多新內容。")
        assertEquals(2, PageContinuation.firstNew(page1, next))
    }

    @Test
    fun aLineThatWasCutByTheScreenEdgeStillCountsAsRead() {
        // the old page showed the whole sentence; the new page shows only its lower part
        val old = listOf("這是一個很長很長的句子，會被畫面邊緣切到。")
        val next = s("會被畫面邊緣切到。", "接著是新的一句話在這裡。")
        assertEquals(1, PageContinuation.firstNew(old, next))
    }

    @Test
    fun aRecognitionMistakeStillCountsAsRead() {
        val next = s("第二個句子也在這襄。", "新的內容從這句開始。")
        assertEquals(1, PageContinuation.firstNew(page1, next))
    }

    @Test
    fun aPageThatRepeatsEverythingIsTheEndOfTheContent() {
        assertNull(PageContinuation.firstNew(page1, s("第二個句子也在這裡。", "第三個句子在最底下。")))
        assertNull(PageContinuation.firstNew(page1, page1.map { Sentence(it, 0, it.length) }))
    }

    @Test
    fun anEmptyPageHasNothingNew() = assertNull(PageContinuation.firstNew(page1, emptyList()))

    @Test
    fun aSentenceRepeatedFarDownDoesNotSkipTheNewContentAbove() {
        // the overlap is a block at the top; a repeat far below it is a coincidence (a footer, a repeated phrase)
        val next = s("全新的一句話在最上面。", "又是新的一句話。", "還是新的一句話。", "仍然是新的一句話。", "第三個句子在最底下。")
        assertEquals(0, PageContinuation.firstNew(page1, next))
    }

    @Test
    fun aSmallGapInsideTheOverlapIsTolerated() {
        val next = s("第一個句子寫在這裡。", "被改動過的完全不同的句子。", "第三個句子在最底下。", "新的內容從這句開始。")
        assertEquals(3, PageContinuation.firstNew(page1, next))
    }

    @Test
    fun shortSentencesSayTooLittleToCountAsRepeats() {
        val old = listOf("已就緒", "使用")
        assertEquals(0, PageContinuation.firstNew(old, s("已就緒", "使用", "新的內容在這裡寫著。")))
    }

    @Test
    fun noOldPageMeansEverythingIsNew() {
        assertEquals(0, PageContinuation.firstNew(emptyList(), s("第一句話在這裡。", "第二句話在這裡。")))
    }

    @Test
    fun numberedSentencesThatDifferOnlyInTheNumberAreNotRepeats() {
        val old = listOf("第15段：這是連續閱讀的測試內容。", "第16段：這是連續閱讀的測試內容。")
        val next = s("第15段：這是連續閱讀的測試內容。", "第16段：這是連續閱讀的測試內容。", "第17段：這是連續閱讀的測試內容。", "第18段：這是連續閱讀的測試內容。")
        // 15 and 16 are repeats; 17 looks almost the same as 16 but is another sentence
        assertEquals(2, PageContinuation.firstNew(old, next))
    }

    @Test
    fun aPageOfTemplatedSentencesIsNotTakenForAllRead() {
        val old = (1..16).map { "第${it}段：這是連續閱讀的測試內容。" }
        val next = s(*(17..30).map { "第${it}段：這是連續閱讀的測試內容。" }.toTypedArray())
        assertEquals(0, PageContinuation.firstNew(old, next))
    }

    @Test
    fun aCutLineKeepsItsNumbersAndStillCountsAsRead() {
        val old = listOf("2024年3月15日發布的第12號公告內容如下。")
        assertEquals(1, PageContinuation.firstNew(old, s("第12號公告內容如下。", "接著是新的一句話在這裡。")))
    }

    @Test
    fun freshShareSaysHowMuchOfTheScrolledPageIsNew() {
        val old = (1..8).map { "這是第${it}個很長很長的句子內容。" }
        // a quarter of a page: 6 of the 8 are repeats
        val quarter = (3..10).map { Sentence("這是第${it}個很長很長的句子內容。", 0, 10) }
        assertEquals(0.25f, PageContinuation.freshShare(old, quarter), 0.001f)
        // a whole page: nothing repeated
        val whole = (9..16).map { Sentence("這是第${it}個很長很長的句子內容。", 0, 10) }
        assertEquals(1f, PageContinuation.freshShare(old, whole), 0.001f)
        // nothing moved
        assertEquals(0f, PageContinuation.freshShare(old, old.map { Sentence(it, 0, 10) }), 0.001f)
    }

    @Test
    fun freshShareOfAnEmptyPageIsAllNew() = assertEquals(1f, PageContinuation.freshShare(page1, emptyList()), 0f)
}
