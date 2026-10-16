package com.ping.verbead.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextStitcherTest {

    @Test
    fun testChineseParagraphNaturalWrap() {
        // 1. 一般中文段落自然折行（消除分隔，無縫拼合）
        // 左對齊長文段落：高度皆為 20f，左邊界皆為 50f，行距 10f
        val block1 = OcrBlock("這是一段長文章的第一行文字內容，長度足夠，", 50f, 100f, 400f, 120f)
        val block2 = OcrBlock("接續著第二行繼續陳述這個自然折行的段落。", 50f, 130f, 380f, 150f)

        val result = TextStitcher.stitch(listOf(block1, block2))
        assertEquals("這是一段長文章的第一行文字內容，長度足夠，接續著第二行繼續陳述這個自然折行的段落。", result)
    }

    @Test
    fun testEnglishHyphenUnwrapping() {
        // 2. 英文連字號折行（如 impor- + tant -> important）
        val block1 = OcrBlock("This is an impor-", 50f, 100f, 300f, 120f)
        val block2 = OcrBlock("tant announcement for all.", 50f, 130f, 350f, 150f)

        val result = TextStitcher.stitch(listOf(block1, block2))
        assertEquals("This is an important announcement for all.", result)
    }

    @Test
    fun testHangulWordSpacingWrap() {
        // 3. 韓文折行（補空格）
        val block1 = OcrBlock("안녕하세요", 50f, 100f, 300f, 120f)
        val block2 = OcrBlock("반갑습니다", 50f, 130f, 300f, 150f)

        val result = TextStitcher.stitch(listOf(block1, block2))
        assertEquals("안녕하세요 반갑습니다", result)
    }

    @Test
    fun testCenteredSloganPackageAdvertising() {
        // 4. 商品包裝廣告文案（字高不同或居中排版時，不誤黏合）
        // 案例 A：短行居中排版「白鴿洗髮精 / 柔順亮麗 / 含保養成分」
        // centerX 皆在 200f，短行寬度約 80f ~ 120f
        val slogan1 = OcrBlock("白鴿洗髮精", 150f, 100f, 250f, 130f)
        val slogan2 = OcrBlock("柔順亮麗", 160f, 140f, 240f, 170f)
        val slogan3 = OcrBlock("含保養成分", 140f, 180f, 260f, 210f)

        val resultSlogan = TextStitcher.stitch(listOf(slogan1, slogan2, slogan3))
        assertEquals("白鴿洗髮精\n柔順亮麗\n含保養成分", resultSlogan)

        // 案例 B：字體高度倍率差異突變（標題 vs 內文，如 40f vs 20f）
        val title = OcrBlock("促銷特賣會", 50f, 100f, 300f, 140f) // height = 40
        val body = OcrBlock("全館商品五折起特惠中", 50f, 155f, 300f, 175f) // height = 20
        val resultRatio = TextStitcher.stitch(listOf(title, body))
        assertEquals("促銷特賣會\n全館商品五折起特惠中", resultRatio)
    }

    @Test
    fun testSentenceEndingPunctuationNewline() {
        // 5. 句末標點符號（正常換行）
        val line1 = OcrBlock("第一段已經結束了。", 50f, 100f, 300f, 120f)
        val line2 = OcrBlock("這是全新的第二段內容", 50f, 130f, 300f, 150f)
        val result = TextStitcher.stitch(listOf(line1, line2))
        assertEquals("第一段已經結束了。\n這是全新的第二段內容", result)

        // 驚嘆號、問號與分號
        val exclamation = OcrBlock("太棒了！", 50f, 100f, 300f, 120f)
        val next = OcrBlock("我們出發吧", 50f, 130f, 300f, 150f)
        assertEquals("太棒了！\n我們出發吧", TextStitcher.stitch(listOf(exclamation, next)))
    }

    @Test
    fun testColonNewline() {
        // 全形與半形冒號換行判定
        val cjkColon = OcrBlock("注意事項：", 50f, 100f, 300f, 120f)
        val cjkContent = OcrBlock("請務必配戴口罩入場", 50f, 130f, 300f, 150f)
        assertEquals("注意事項：\n請務必配戴口罩入場", TextStitcher.stitch(listOf(cjkColon, cjkContent)))

        val latinColon = OcrBlock("Note:", 50f, 100f, 200f, 120f)
        val latinContent = OcrBlock("Keep away from fire.", 50f, 130f, 300f, 150f)
        assertEquals("Note:\nKeep away from fire.", TextStitcher.stitch(listOf(latinColon, latinContent)))
    }

    @Test
    fun testStrippingClosingQuotesAndBrackets() {
        // 帶引號、括號的複合句末標點識別換行
        val quoted1 = OcrBlock("「這是一個重要的公告。」", 50f, 100f, 350f, 120f)
        val next1 = OcrBlock("請大家互相轉告配合", 50f, 130f, 300f, 150f)
        assertEquals("「這是一個重要的公告。」\n請大家互相轉告配合", TextStitcher.stitch(listOf(quoted1, next1)))

        val quotedExclamation = OcrBlock("“Are you ready?”", 50f, 100f, 300f, 120f)
        val next2 = OcrBlock("Yes, we are.", 50f, 130f, 300f, 150f)
        assertEquals("“Are you ready?”\nYes, we are.", TextStitcher.stitch(listOf(quotedExclamation, next2)))

        val bracketColon = OcrBlock("（備註事項：）", 50f, 100f, 300f, 120f)
        val next3 = OcrBlock("逾期不予受理", 50f, 130f, 300f, 150f)
        assertEquals("（備註事項：）\n逾期不予受理", TextStitcher.stitch(listOf(bracketColon, next3)))
    }

    @Test
    fun testHorizontalInlineBoxes() {
        // 同一水平行橫向相鄰文字框不誤判換行
        // 姓名： 與 王小明 在同一高度 y: 100f ~ 120f
        val b1 = OcrBlock("姓名：", 50f, 100f, 110f, 120f)
        val b2 = OcrBlock("王小明", 115f, 100f, 175f, 120f)
        assertEquals("姓名：王小明", TextStitcher.stitch(listOf(b1, b2)))

        // 英文橫向相鄰且有間距
        val b3 = OcrBlock("Name:", 50f, 100f, 100f, 120f)
        val b4 = OcrBlock("Alice", 115f, 100f, 160f, 120f) // deltaX = 15 > 0.35 * 20 = 7
        assertEquals("Name: Alice", TextStitcher.stitch(listOf(b3, b4)))
    }

    @Test
    fun testEnglishPeriodAndAbbreviation() {
        // 縮寫不換行
        val abbr1 = OcrBlock("Please visit Dr.", 50f, 100f, 300f, 120f)
        val body1 = OcrBlock("Watson at his office.", 50f, 130f, 300f, 150f)
        assertEquals("Please visit Dr. Watson at his office.", TextStitcher.stitch(listOf(abbr1, body1)))

        val abbr2 = OcrBlock("Buy fruits e.g.", 50f, 100f, 300f, 120f)
        val body2 = OcrBlock("apples and bananas.", 50f, 130f, 300f, 150f)
        assertEquals("Buy fruits e.g. apples and bananas.", TextStitcher.stitch(listOf(abbr2, body2)))

        // 正常英文句末且下一行首字大寫：換行
        val endLine = OcrBlock("This is the first sentence.", 50f, 100f, 350f, 120f)
        val nextSentence = OcrBlock("Second sentence begins here.", 50f, 130f, 350f, 150f)
        assertEquals("This is the first sentence.\nSecond sentence begins here.", TextStitcher.stitch(listOf(endLine, nextSentence)))
    }

    @Test
    fun testMultiColumnUpwardJump() {
        // 多欄排版中 Y 軸向上大幅跳躍判定為換行
        val leftColumnBottom = OcrBlock("左欄底部最後一行文字", 50f, 500f, 250f, 520f)
        val rightColumnTop = OcrBlock("右欄頂部第一行文字", 300f, 100f, 500f, 120f)
        assertEquals("左欄底部最後一行文字\n右欄頂部第一行文字", TextStitcher.stitch(listOf(leftColumnBottom, rightColumnTop)))
    }

    @Test
    fun testPunctuationSpacingGuard() {
        // 標點防空格守衛：避免開括號後或閉括號前插入多餘空格
        val openBracket = OcrBlock("請參考（", 50f, 100f, 200f, 120f)
        val content = OcrBlock("附錄A）", 50f, 130f, 200f, 150f)
        assertEquals("請參考（附錄A）", TextStitcher.stitch(listOf(openBracket, content)))
    }

    @Test
    fun testPanguSpacingOption() {
        // 中英混排盤古之白配置
        val mixed1 = OcrBlock("請購買全新推出的iPhone", 50f, 100f, 350f, 120f)
        val mixed2 = OcrBlock("保護套與配件", 50f, 130f, 300f, 150f)

        // 預設關閉盤古之白（無縫貼合）
        assertEquals("請購買全新推出的iPhone保護套與配件", TextStitcher.stitch(listOf(mixed1, mixed2), panguSpacing = false))

        // 開啟盤古之白（中英交界補 1 空格）
        assertEquals("請購買全新推出的iPhone 保護套與配件", TextStitcher.stitch(listOf(mixed1, mixed2), panguSpacing = true))
    }

    @Test
    fun testListItemNewline() {
        // 列表項強制換行
        val line1 = OcrBlock("請確認下列事項：", 50f, 100f, 300f, 120f)
        val item1 = OcrBlock("1. 攜帶身分證明文件", 50f, 130f, 300f, 150f)
        val item2 = OcrBlock("2. 準時抵達會場", 50f, 160f, 300f, 180f)
        val bullet = OcrBlock("• 遵守現場防疫規則", 50f, 190f, 300f, 210f)

        val result = TextStitcher.stitch(listOf(line1, item1, item2, bullet))
        assertEquals("請確認下列事項：\n1. 攜帶身分證明文件\n2. 準時抵達會場\n• 遵守現場防疫規則", result)
    }

    @Test
    fun testVerticalGapAndIndentation() {
        // 垂直行距過大（大於 1.2 * height）
        val block1 = OcrBlock("段落一內容", 50f, 100f, 300f, 120f) // height = 20, bottom = 120
        val block2 = OcrBlock("段落二內容", 50f, 150f, 300f, 170f) // deltaY = 30 > 24
        assertEquals("段落一內容\n段落二內容", TextStitcher.stitch(listOf(block1, block2)))

        // 顯著縮排（curr.left - prev.left > 1.5 * height）
        val p1 = OcrBlock("前一段落最後一句", 50f, 100f, 300f, 120f) // height = 20
        val p2 = OcrBlock("新段落首行縮排開始", 90f, 130f, 350f, 150f) // deltaX = 40 > 30
        assertEquals("前一段落最後一句\n新段落首行縮排開始", TextStitcher.stitch(listOf(p1, p2)))
    }

    @Test
    fun testCjkLatinMixedBoundary() {
        // CJK 與 Latin / 數字混排交界（預設無縫貼合）
        val b1 = OcrBlock("請購買全新推出的iPhone", 50f, 100f, 350f, 120f)
        val b2 = OcrBlock("保護套與配件", 50f, 130f, 300f, 150f)
        assertEquals("請購買全新推出的iPhone保護套與配件", TextStitcher.stitch(listOf(b1, b2)))

        val b3 = OcrBlock("請參閱第12", 50f, 100f, 300f, 120f)
        val b4 = OcrBlock("條規定辦理", 50f, 130f, 300f, 150f)
        assertEquals("請參閱第12條規定辦理", TextStitcher.stitch(listOf(b3, b4)))
    }

    @Test
    fun testEnglishNaturalWrap() {
        // 純 Latin 語言折行（補空格）
        val l1 = OcrBlock("This is the first line of an English", 50f, 100f, 350f, 120f)
        val l2 = OcrBlock("paragraph that wraps naturally.", 50f, 130f, 350f, 150f)
        assertEquals("This is the first line of an English paragraph that wraps naturally.", TextStitcher.stitch(listOf(l1, l2)))
    }

    @Test
    fun testPerformanceUnderOneMillisecond() {
        // 極致性能需求：總耗時必須 < 1ms
        val blocks = listOf(
            OcrBlock("在繁華熱鬧的城市中心，人們匆匆忙忙地走過每一個", 50f, 100f, 400f, 120f),
            OcrBlock("轉角與街道，尋找屬於自己的寧靜與溫暖。", 50f, 130f, 380f, 150f),
            OcrBlock("This application provides a completely offline", 50f, 170f, 400f, 190f),
            OcrBlock("and secure optical character recognition service.", 50f, 200f, 390f, 220f)
        )

        // Warm up
        repeat(50) { TextStitcher.stitch(blocks) }

        val start = System.nanoTime()
        val result = TextStitcher.stitch(blocks)
        val durationNanos = System.nanoTime() - start
        val durationMs = durationNanos / 1_000_000.0

        assertTrue("Execution should take less than 1ms, took ${durationMs}ms", durationMs < 1.0)
        assertTrue(result.isNotEmpty())
    }
}
