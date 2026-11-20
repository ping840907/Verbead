package com.ping.verbead.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextSimilarityTest {

    @Test
    fun identicalTextScoresOne() = assertEquals(1f, TextSimilarity.containment("你好世界", "你好世界"), 0f)

    @Test
    fun punctuationAndSpacesDoNotMatter() =
        assertEquals(1f, TextSimilarity.containment("Hello, world!", "hello   world"), 0f)

    @Test
    fun aLineInsideALongerTextIsContained() =
        assertEquals(1f, TextSimilarity.containment("第二行的內容", "第一行的內容 第二行的內容 第三行的內容"), 0f)

    @Test
    fun oneWrongCharacterStillScoresHigh() {
        val score = TextSimilarity.containment("今天天氣很畀好", "今天天氣很好")
        assertTrue("was $score", score >= 0.6f)
    }

    @Test
    fun unrelatedTextScoresLow() {
        assertTrue(TextSimilarity.containment("完全不同的一句話", "Settings and preferences") < 0.2f)
    }

    @Test
    fun singleCharactersAreComparedOnTheirOwn() {
        assertEquals(1f, TextSimilarity.containment("好", "你好"), 0f)
        assertEquals(0f, TextSimilarity.containment("壞", "你好"), 0f)
    }

    @Test
    fun emptyOrPunctuationOnlyIsZero() {
        assertEquals(0f, TextSimilarity.containment("", "abc"), 0f)
        assertEquals(0f, TextSimilarity.containment("abc", ""), 0f)
        assertEquals(0f, TextSimilarity.containment("...", "abc"), 0f)
        assertEquals(0, TextSimilarity.letterCount("!!! ..."))
        assertEquals(3, TextSimilarity.letterCount("a b-c"))
    }
}

class HybridMergerTest {

    private fun item(l: Int, t: Int, r: Int, b: Int, text: String) = ScreenTextItem(l, t, r, b, text)

    @Test
    fun aRecognitionMistakeIsReplacedByTheTreesText() {
        val tree = listOf(item(0, 0, 400, 40, "今天天氣很好"))
        val ocr = listOf(item(0, 2, 400, 38, "今天天氣很畀"))
        val r = HybridMerger.merge(tree, ocr)
        assertEquals(1, r.matched)
        assertEquals(0, r.inserted)
        assertEquals("今天天氣很好", ReadingOrder.toText(r.items))
    }

    @Test
    fun textInAPictureIsAddedAtItsPlace() {
        val tree = listOf(item(0, 0, 400, 40, "標題"), item(0, 400, 400, 440, "頁尾"))
        val ocr = listOf(item(50, 200, 350, 240, "圖片裡的字"))
        val r = HybridMerger.merge(tree, ocr)
        assertEquals(1, r.inserted)
        assertEquals("標題\n圖片裡的字\n頁尾", ReadingOrder.toText(r.items))
    }

    @Test
    fun aPictureWithADescriptionStillGetsItsOwnText() {
        // the picture reports "照片" as its description; what is written inside it is something else
        val tree = listOf(item(0, 100, 400, 400, "照片"))
        val ocr = listOf(item(40, 200, 360, 240, "特價三天"))
        val r = HybridMerger.merge(tree, ocr)
        assertEquals(1, r.inserted)
        assertTrue(ReadingOrder.toText(r.items).contains("特價三天"))
    }

    @Test
    fun everyLineOfAParagraphNodeIsMatchedAgainstTheWholeNode() {
        val tree = listOf(item(0, 0, 400, 120, "第一行的內容很長。第二行的內容也很長。第三行的內容到此結束。"))
        val ocr = listOf(
            item(0, 0, 400, 38, "第一行的內容很長。"),
            item(0, 40, 400, 78, "第二行的內容也很長。"),
            item(0, 80, 400, 118, "第三行的內容到此結朿。"), // one wrong character
        )
        val r = HybridMerger.merge(tree, ocr)
        assertEquals(3, r.matched)
        assertEquals(0, r.inserted)
        assertEquals(1, r.items.size)
    }

    @Test
    fun differentWritingOnTopOfATextNodeIsKept() {
        // a caption node with a picture behind it that has writing of its own
        val tree = listOf(item(0, 300, 400, 340, "Photo by Anna"))
        val ocr = listOf(item(20, 310, 380, 335, "全館五折優惠中"))
        val r = HybridMerger.merge(tree, ocr)
        assertEquals(1, r.inserted)
    }

    @Test
    fun strayGlyphsAreNeverAdded() {
        val r = HybridMerger.merge(emptyList(), listOf(item(0, 0, 30, 30, "个"), item(0, 40, 30, 70, "…")))
        assertEquals(0, r.inserted)
        assertTrue(r.items.isEmpty())
    }

    @Test
    fun withoutATreeEverythingRecognizedIsKept() {
        val r = HybridMerger.merge(emptyList(), listOf(item(0, 0, 100, 30, "第一行"), item(0, 50, 100, 80, "第二行")))
        assertEquals(2, r.inserted)
        assertEquals("第一行\n第二行", ReadingOrder.toText(r.items))
    }

    @Test
    fun withoutRecognitionTheTreeIsUnchanged() {
        val tree = listOf(item(0, 0, 100, 30, "甲"), item(0, 50, 100, 80, "乙"))
        val r = HybridMerger.merge(tree, emptyList())
        assertEquals(tree, r.items)
        assertEquals(0, r.inserted + r.matched)
    }

    @Test
    fun aLineBesideATreeItemThatDoesNotTouchItIsAdded() {
        val tree = listOf(item(0, 0, 100, 40, "左邊"))
        val ocr = listOf(item(300, 0, 500, 40, "右邊的字"))
        assertEquals(1, HybridMerger.merge(tree, ocr).inserted)
    }

    @Test
    fun aLabelThatSaysTheSameIsNotRepeated() {
        val tree = listOf(item(0, 0, 100, 40, "分享"))
        val ocr = listOf(item(0, 0, 100, 40, "分享"))
        val r = HybridMerger.merge(tree, ocr)
        assertEquals(0, r.inserted)
        assertEquals(1, r.matched)
    }
}
