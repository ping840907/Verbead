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
        assertEquals("請購買全新推出的 iPhone 保護套與配件", TextStitcher.stitch(listOf(mixed1, mixed2), panguSpacing = true))
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
        // 垂直行距過大（量不到典型行距時，超過 2.2 倍框高）
        val block1 = OcrBlock("段落一內容", 50f, 100f, 300f, 120f) // height = 20, bottom = 120
        val block2 = OcrBlock("段落二內容", 50f, 180f, 300f, 200f) // deltaY = 60 = 3h, well past natural leading
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

    @Test
    fun testFullTextPanguSpacing() {
        val text1 = "請購買全新推出的iPhone保護套與配件"
        assertEquals("請購買全新推出的 iPhone 保護套與配件", TextStitcher.applyPanguSpacing(text1))

        val text2 = "共有128個檔案"
        assertEquals("共有 128 個檔案", TextStitcher.applyPanguSpacing(text2))

        val block = OcrBlock("在2026年使用Android系統體驗AI技術", 50f, 100f, 300f, 120f)
        val stitched = TextStitcher.stitch(listOf(block), panguSpacing = true)
        assertEquals("在 2026 年使用 Android 系統體驗 AI 技術", stitched)
    }

    @Test
    fun testOcrToTraditionalWithOpenCC() {
        val simplifiedBlock1 = OcrBlock("这是简体中文光学字符识别测试，", 50f, 100f, 400f, 120f)
        val simplifiedBlock2 = OcrBlock("包含台湾在地化用词与异体字校正。", 50f, 130f, 400f, 150f)

        // 1. toTraditional = false: 保留簡體原字
        val rawResult = TextStitcher.stitch(listOf(simplifiedBlock1, simplifiedBlock2), toTraditional = false)
        assertEquals("这是简体中文光学字符识别测试，包含台湾在地化用词与异体字校正。", rawResult)

        // 2. toTraditional = true: 轉為台灣繁體中文 (含 OpenCC 與台灣常用異體字校正)
        val tradResult = TextStitcher.stitch(listOf(simplifiedBlock1, simplifiedBlock2), toTraditional = true)
        assertEquals("這是簡體中文光學字符識別測試，包含臺灣在地化用詞與異體字校正。", tradResult)

        // 3. 結合盤古之白 (pangu spacing) 與繁體轉換
        val mixedBlock = OcrBlock("在2026年使用Android系统体验AI技术", 50f, 100f, 300f, 120f)
        val tradPanguResult = TextStitcher.stitch(listOf(mixedBlock), panguSpacing = true, toTraditional = true)
        assertEquals("在 2026 年使用 Android 系統體驗 AI 技術", tradPanguResult)
    }

    @Test
    fun testOcrJapaneseBlockIsNotConvertedToTraditionalKanji() {
        // OCR 與語音共用同一套轉換：含假名的日文不得被改成繁體字形（国→國）
        val ja = OcrBlock("国の学校で会議があります。", 50f, 100f, 400f, 120f)
        assertEquals("国の学校で会議があります。", TextStitcher.stitch(listOf(ja), toTraditional = true))
    }

    @Test
    fun testOcrMixedChineseAndJapaneseConvertsOnlyTheChinese() {
        val zh = OcrBlock("这个专案要上线。", 50f, 100f, 400f, 120f)
        val ja = OcrBlock("国の学校です。", 50f, 200f, 400f, 220f)
        val result = TextStitcher.stitch(listOf(zh, ja), toTraditional = true)
        assertTrue(result, result.contains("這個專案要上線。"))
        assertTrue(result, result.contains("国の学校です。"))
    }

    @Test
    fun testCommaEndingJoinsDespiteIndentation() {
        // 縮排本來會斷行，但行尾是逗號代表句子未完
        val p1 = OcrBlock("前一段落最後一句，", 50f, 100f, 300f, 120f)
        val p2 = OcrBlock("縮排開始的下半句", 90f, 130f, 350f, 150f)
        assertEquals("前一段落最後一句，縮排開始的下半句", TextStitcher.stitch(listOf(p1, p2)))
    }

    @Test
    fun testCommaEndingJoinsCenteredShortLines() {
        val s1 = OcrBlock("白鴿洗髮精，", 150f, 100f, 250f, 130f)
        val s2 = OcrBlock("柔順亮麗", 160f, 140f, 240f, 170f)
        assertEquals("白鴿洗髮精，柔順亮麗", TextStitcher.stitch(listOf(s1, s2)))
    }

    @Test
    fun testCommaEndingToleratesLargerLineGapButNotHuge() {
        val a = OcrBlock("段落一，", 50f, 100f, 300f, 120f)
        val near = OcrBlock("段落二", 50f, 150f, 300f, 170f) // deltaY = 30 > 1.2h，但仍在逗號容許範圍
        assertEquals("段落一，段落二", TextStitcher.stitch(listOf(a, near)))

        val far = OcrBlock("段落二", 50f, 200f, 300f, 220f) // deltaY = 80 > 2h
        assertEquals("段落一，\n段落二", TextStitcher.stitch(listOf(a, far)))
    }

    @Test
    fun testCommaEndingStillBreaksOnColumnJumpAndListItem() {
        val left = OcrBlock("左欄底部，", 50f, 500f, 250f, 520f)
        val right = OcrBlock("右欄頂部", 300f, 100f, 500f, 120f)
        assertEquals("左欄底部，\n右欄頂部", TextStitcher.stitch(listOf(left, right)))

        val head = OcrBlock("請準備，", 50f, 100f, 300f, 120f)
        val item = OcrBlock("1. 身分證", 50f, 130f, 300f, 150f)
        assertEquals("請準備，\n1. 身分證", TextStitcher.stitch(listOf(head, item)))
    }

    @Test
    fun testLineStartingWithCommaJoinsPrevious() {
        val p1 = OcrBlock("這是第一句", 50f, 100f, 300f, 120f)
        val p2 = OcrBlock("，接著第二句", 90f, 130f, 350f, 150f)
        assertEquals("這是第一句，接著第二句", TextStitcher.stitch(listOf(p1, p2)))
    }

    @Test
    fun testCommaSeparatorByScript() {
        // 全形逗號後接英文：不補空格
        val zh = OcrBlock("請使用，", 50f, 100f, 300f, 120f)
        val en = OcrBlock("Android", 50f, 130f, 300f, 150f)
        assertEquals("請使用，Android", TextStitcher.stitch(listOf(zh, en)))

        // 半形逗號後接英文：補空格
        val e1 = OcrBlock("Hello,", 150f, 100f, 250f, 120f)
        val e2 = OcrBlock("world", 160f, 130f, 240f, 150f)
        assertEquals("Hello, world", TextStitcher.stitch(listOf(e1, e2)))

        // 數字千分位被折行
        val n1 = OcrBlock("共計 1,", 50f, 100f, 300f, 120f)
        val n2 = OcrBlock("000 元", 50f, 130f, 300f, 150f)
        assertEquals("共計 1,000 元", TextStitcher.stitch(listOf(n1, n2)))
    }

    @Test
    fun testFirstLineIndentJoinsFollowingLines() {
        // 首行縮排（右邊界對齊、左邊界內縮）不是居中，要和後面的行接起來；下一段的縮排首行才斷行
        val l1 = OcrBlock("這是第一段落縮排的首行文字內容很長很長", 90f, 100f, 400f, 120f)
        val l2 = OcrBlock("接續上一行沒有縮排的第二行文字內容", 50f, 130f, 400f, 150f)
        val l3 = OcrBlock("第一段落最後一行", 50f, 160f, 300f, 180f)
        val l4 = OcrBlock("第二段落縮排的首行文字內容很長很長", 90f, 190f, 400f, 210f)
        val l5 = OcrBlock("接續第二段落的下一行文字內容", 50f, 220f, 400f, 240f)
        assertEquals(
            "這是第一段落縮排的首行文字內容很長很長接續上一行沒有縮排的第二行文字內容第一段落最後一行\n" +
                "第二段落縮排的首行文字內容很長很長接續第二段落的下一行文字內容",
            TextStitcher.stitch(listOf(l1, l2, l3, l4, l5))
        )
    }

    @Test
    fun testFirstLineIndentWithPhotoNoiseOnRightEdge() {
        // 實拍報紙：首行縮排約 0.7 字，右邊界有幾個像素的雜訊（下一行反而多出 5px）
        val l1 = OcrBlock("臺灣5人制棒球代表隊（上圖）昨在", 355f, 740f, 1225f, 810f)
        val l2 = OcrBlock("達卡青奧金牌戰以1:2、1：7不敵世界", 305f, 820f, 1230f, 890f)
        val l3 = OcrBlock("排名第一的古巴拿到銀牌。", 303f, 900f, 1100f, 970f)
        assertEquals(
            "臺灣5人制棒球代表隊（上圖）昨在達卡青奧金牌戰以1:2、1：7不敵世界排名第一的古巴拿到銀牌。",
            TextStitcher.stitch(listOf(l1, l2, l3))
        )

        // 右邊界雜訊朝另一個方向也一樣
        val m1 = OcrBlock("武術小將朱宥恩（下圖）在女子太", 360f, 1260f, 1250f, 1330f)
        val m2 = OcrBlock("極拳／太極扇發揮亮眼，最終以總分", 295f, 1340f, 1245f, 1410f)
        assertEquals("武術小將朱宥恩（下圖）在女子太極拳／太極扇發揮亮眼，最終以總分", TextStitcher.stitch(listOf(m1, m2)))
    }

    @Test
    fun testCenteredTitleStillBreaks() {
        // 左右邊界反向內縮才是居中，仍然斷行
        val title = OcrBlock("年度報告重點摘要整理", 100f, 100f, 400f, 120f)
        val sub = OcrBlock("二零二六年版本", 150f, 130f, 350f, 150f)
        assertEquals("年度報告重點摘要整理\n二零二六年版本", TextStitcher.stitch(listOf(title, sub)))
    }

    @Test
    fun testTextFollowedByNumberOrEnglishStaysJoined() {
        // 小數、型號開頭不是列表編號
        val a1 = OcrBlock("本次出貨總重量共計", 50f, 100f, 300f, 120f)
        val a2 = OcrBlock("12.5公斤請確認", 50f, 130f, 300f, 150f)
        assertEquals("本次出貨總重量共計12.5公斤請確認", TextStitcher.stitch(listOf(a1, a2)))

        val b1 = OcrBlock("使用的耳機接頭規格為", 50f, 100f, 300f, 120f)
        val b2 = OcrBlock("3.5mm 與 USB Type-C", 50f, 130f, 300f, 150f)
        assertEquals("使用的耳機接頭規格為3.5mm 與 USB Type-C", TextStitcher.stitch(listOf(b1, b2)))

        // 負號、縮寫不是項目符號
        val c1 = OcrBlock("今天最低氣溫降到", 50f, 100f, 300f, 120f)
        val c2 = OcrBlock("-5°C 請多加衣物", 50f, 130f, 300f, 150f)
        assertEquals("今天最低氣溫降到-5°C 請多加衣物", TextStitcher.stitch(listOf(c1, c2)))

        val d1 = OcrBlock("本公司總部位於", 50f, 100f, 300f, 120f)
        val d2 = OcrBlock("U.S. 西岸地區", 50f, 130f, 300f, 150f)
        assertEquals("本公司總部位於U.S. 西岸地區", TextStitcher.stitch(listOf(d1, d2)))

        // 真正的列表項目仍然斷行
        val e1 = OcrBlock("請確認下列事項", 50f, 100f, 300f, 120f)
        val e2 = OcrBlock("A. 身分證明", 50f, 130f, 300f, 150f)
        val e3 = OcrBlock("- 印章", 50f, 160f, 300f, 180f)
        assertEquals("請確認下列事項\nA. 身分證明\n- 印章", TextStitcher.stitch(listOf(e1, e2, e3)))
    }

    private fun lines(vararg texts: String): List<OcrBlock> =
        texts.mapIndexed { i, t -> OcrBlock(t, 50f, 100f + 30f * i, 300f, 120f + 30f * i) }

    private fun stitchLines(vararg texts: String) = TextStitcher.stitch(lines(*texts))

    @Test
    fun testJapaneseWrapAndListMarkers() {
        // 假名、漢字之間換行不補空格
        assertEquals("これは日本語の文章です", stitchLines("これは日本語の", "文章です"))
        // 日文項目符號與註記起首行斷行
        assertEquals("確認事項\n・本人確認書類\n※注意", stitchLines("確認事項", "・本人確認書類", "※注意"))
        assertEquals("手順\n①開く\n②閉じる", stitchLines("手順", "①開く", "②閉じる"))
        assertEquals("条件\n（１）第一項\n（２）第二項", stitchLines("条件", "（１）第一項", "（２）第二項"))
        // 日文讀點結尾視為句子未完
        assertEquals("まず、次に", stitchLines("まず、", "次に"))
    }

    @Test
    fun testKoreanListMarkers() {
        assertEquals("안녕하세요 반갑습니다", stitchLines("안녕하세요", "반갑습니다"))
        assertEquals("항목\n가. 첫째\n나. 둘째", stitchLines("항목", "가. 첫째", "나. 둘째"))
    }

    @Test
    fun testCyrillicHyphenAndAbbreviations() {
        assertEquals("Это осуществляется легко", stitchLines("Это осущест-", "вляется легко"))
        // 專有複合詞保留連字號
        assertEquals("город Санкт-Петербург", stitchLines("город Санкт-", "Петербург"))
        // т.д. 是縮寫，不斷行
        assertEquals("Овощи, фрукты и т.д. Дальше идёт текст", stitchLines("Овощи, фрукты и т.д.", "Дальше идёт текст"))
        // 一般句末後大寫開頭 -> 新句
        assertEquals("Это конец.\nНовое предложение", stitchLines("Это конец.", "Новое предложение"))
        // 俄文單字母編號
        assertEquals("Пункты\nа) первый\nб) второй", stitchLines("Пункты", "а) первый", "б) второй"))
    }

    @Test
    fun testGermanFrenchSpanishAbbreviationsAndHyphens() {
        assertEquals("Früchte z.B. Äpfel und Birnen.", stitchLines("Früchte z.B.", "Äpfel und Birnen."))
        assertEquals("Wir spielen Fußball heute", stitchLines("Wir spielen Fuß-", "ball heute"))
        assertEquals("im Bezirk Berlin-Mitte gelegen", stitchLines("im Bezirk Berlin-", "Mitte gelegen"))
        assertEquals("Hable con la Sra. García mañana", stitchLines("Hable con la Sra.", "García mañana"))
        // 法文引號：開引號後不補空格
        assertEquals("Il a dit «bonjour»", stitchLines("Il a dit «", "bonjour»"))
        // U+2010 與軟連字號
        assertEquals("international", stitchLines("inter‐", "national"))
        assertEquals("international", stitchLines("inter­", "national"))
    }

    @Test
    fun testVietnameseExtendedLatinIsLatin() {
        assertTrue(TextStitcher.isLatin('ệ'))
        assertTrue(TextStitcher.isLatin('ạ'))
        assertEquals("Tiếng Việt rất hay", stitchLines("Tiếng Việt", "rất hay"))
    }

    @Test
    fun testArabicHebrewHindiSentenceEnds() {
        // 句號後接無大小寫的文字 -> 新句
        assertEquals("هذه جملة.\nجملة جديدة", stitchLines("هذه جملة.", "جملة جديدة"))
        assertEquals("This is it.\n你好", stitchLines("This is it.", "你好"))
        // 阿拉伯問號、梵文 danda 是句末
        assertEquals("ماذا تريد؟\nلا شيء", stitchLines("ماذا تريد؟", "لا شيء"))
        assertEquals("यह एक वाक्य है।\nयह दूसरा", stitchLines("यह एक वाक्य है।", "यह दूसरा"))
        // 阿拉伯逗號 -> 連接
        assertEquals("أولا، ثانيا", stitchLines("أولا،", "ثانيا"))
        // 阿拉伯-印度數字編號
        assertEquals("البنود\n١. الأول", stitchLines("البنود", "١. الأول"))
    }

    @Test
    fun testThaiWrapJoinsWithoutSpace() {
        assertEquals("สวัสดีครับยินดีที่ได้รู้จัก", stitchLines("สวัสดีครับ", "ยินดีที่ได้รู้จัก"))
    }

    @Test
    fun testTruncatedUrlWrapIsJoined() {
        val b1 = OcrBlock("Docs: https://example.com/very/long/pa", 50f, 100f, 400f, 120f)
        val b2 = OcrBlock("th/file.html", 50f, 130f, 200f, 150f)
        assertEquals("Docs: https://example.com/very/long/path/file.html", TextStitcher.stitch(listOf(b1, b2)))
    }

    @Test
    fun testUrlWrapDoesNotAbsorbUnrelatedText() {
        val b1 = OcrBlock("see https://example.com/a/b-", 50f, 100f, 400f, 120f)
        val b2 = OcrBlock("c/d.html then continue reading", 50f, 130f, 390f, 150f)
        assertEquals("see https://example.com/a/b-c/d.html then continue reading", TextStitcher.stitch(listOf(b1, b2)))
    }

    @Test
    fun testUrlFollowedByUnrelatedLineIsNotMerged() {
        // URL 行未達最寬行寬（自然結束），下一行是不相干文字
        val b1 = OcrBlock("https://example.com/", 50f, 100f, 200f, 120f)
        val b2 = OcrBlock("Contact us for more information today", 50f, 130f, 400f, 150f)
        val result = TextStitcher.stitch(listOf(b1, b2))
        assertEquals("https://example.com/ Contact us for more information today", result)
    }

    @Test
    fun testMultiLineUrlWrap() {
        val b1 = OcrBlock("https://example.com/aaaa", 50f, 100f, 400f, 120f)
        val b2 = OcrBlock("bbbb/cccc", 50f, 130f, 395f, 150f)
        val b3 = OcrBlock("dddd?x=1", 50f, 160f, 150f, 180f)
        assertEquals("https://example.com/aaaabbbb/ccccdddd?x=1", TextStitcher.stitch(listOf(b1, b2, b3)))
    }

    @Test
    fun testUrlThenListItemNotMerged() {
        val b1 = OcrBlock("https://example.com/path/", 50f, 100f, 400f, 120f)
        val b2 = OcrBlock("2. next item", 50f, 130f, 200f, 150f)
        val result = TextStitcher.stitch(listOf(b1, b2))
        assertEquals("https://example.com/path/" + "\n" + "2. next item", result)
    }

    // ── 實測：偵測框只有字形的七成，行距約 2 倍字高（網頁文章截圖，座標取自手機上的實際偵測結果）──

    private fun articleLines(): List<OcrBlock> = listOf(
        OcrBlock("全球AI晶片龍頭輝達（NVIDIA）執行長，AI教", 38f, 499f, 1159f, 532f),
        OcrBlock("父黃仁勳驚傳與涉嫌涉及中國情報活動的女商", 40f, 594f, 1156f, 630f),
        OcrBlock("人關係密切，更坦承曾對妻子不忠，輝達董事", 40f, 692f, 1156f, 725f),
        OcrBlock("會已宣布展開獨立調查。消息震撼科技圈，今", 40f, 790f, 1156f, 823f),
        OcrBlock("周一（9日）開盤一度下跌約2.9%，最低報", 38f, 885f, 1096f, 920f),
        OcrBlock("180.15美元。市場預料，台積電（2330）、", 38f, 983f, 1102f, 1018f),
        OcrBlock("京元電子（2449）、緯創（3231）等亞洲供", 38f, 1081f, 1126f, 1116f),
        OcrBlock("應商股價也可能出現波動。", 38f, 1179f, 686f, 1211f),
        OcrBlock("而台股近期同樣劇烈震盪，讓不少投資人帳面", 38f, 1361f, 1159f, 1396f),
        OcrBlock("獲利大幅縮水。有號稱「南港巴菲特」的藝", 38f, 1458f, 1102f, 1491f),
        OcrBlock("人黃鐙輝，過去靠著長期布局台積電及0050", 43f, 1556f, 1113f, 1589f),
        OcrBlock("等標的，累積上千萬元投資成果，沒想到這", 40f, 1651f, 1102f, 1684f),
        OcrBlock("波股災也讓他吃足苦頭。黃鐙輝近日宣傳新劇", 40f, 1749f, 1156f, 1782f),
        OcrBlock("時透露，自己過去曾買進部分個股，帳面報酬", 38f, 1847f, 1159f, 1879f),
        OcrBlock("率一度高達100%至200%，不料市場突然重", 38f, 1945f, 1110f, 1977f),
        OcrBlock("挫，原本累積的獲利竟在短時間內幾乎全部蒸", 35f, 2042f, 1159f, 2075f),
        OcrBlock("發，讓他深刻體會股市風險。", 38f, 2138f, 746f, 2173f)
    )

    @Test
    fun testGenerousLeadingWithShrunkBoxesStillWrapsIntoParagraphs() {
        val lines = articleLines()
        assertEquals(98f, TextStitcher.estimateLinePitch(lines), 5f)
        val result = TextStitcher.stitch(lines)
        val paragraphs = result.split("\n")
        assertEquals(2, paragraphs.size) // only the real paragraph gap (4.7x box height) breaks
        assertEquals(
            "全球AI晶片龍頭輝達（NVIDIA）執行長，AI教父黃仁勳驚傳與涉嫌涉及中國情報活動的女商人關係密切，更坦承曾對妻子不忠，輝達董事會已宣布展開獨立調查。" +
                "消息震撼科技圈，今周一（9日）開盤一度下跌約2.9%，最低報180.15美元。市場預料，台積電（2330）、京元電子（2449）、緯創（3231）等亞洲供應商股價也可能出現波動。",
            paragraphs[0]
        )
        assertTrue(paragraphs[1].startsWith("而台股近期同樣劇烈震盪，讓不少投資人帳面獲利大幅縮水。"))
        assertTrue(paragraphs[1].endsWith("讓他深刻體會股市風險。"))
    }

    @Test
    fun testTwoLinesWithGenerousLeadingStillJoinWithoutAPitchEstimate() {
        val lines = articleLines().take(2) // one pair: no page-wide pitch, the looser fixed limit applies
        assertEquals("全球AI晶片龍頭輝達（NVIDIA）執行長，AI教父黃仁勳驚傳與涉嫌涉及中國情報活動的女商", TextStitcher.stitch(lines))
    }

    @Test
    fun testSpacedOutShortItemsAreNotMergedIntoOneLine() {
        // a menu: short items with ~3x pitch and no punctuation must stay separate lines
        val items = listOf("首頁", "最新消息", "熱門話題", "影音專區", "會員中心").mapIndexed { i, t ->
            OcrBlock(t, 50f, 100f + i * 100f, 110f + i * 15f, 134f + i * 100f)
        }
        assertEquals(items.joinToString("\n") { it.text }, TextStitcher.stitch(items))
    }
}
