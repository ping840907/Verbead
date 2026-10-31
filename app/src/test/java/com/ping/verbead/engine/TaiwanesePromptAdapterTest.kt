package com.ping.verbead.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 台灣腔 Prompt 適配器單元測試，驗證破音字與讀音差異校正邏輯。
 */
class TaiwanesePromptAdapterTest {

    @Test
    fun testQiAlwaysFourthTone() {
        // 企：在台灣一律讀四聲 qì (氣)
        val input = "這家企業的企劃和企圖心都很強，連企鵝公仔也很有特色。"
        val adapted = TaiwanesePromptAdapter.adapt(input)
        assertTrue("企業應轉換為氣業以發四聲", adapted.contains("氣業"))
        assertTrue("企劃應轉換為氣劃以發四聲", adapted.contains("氣劃"))
        assertTrue("企圖應轉換為氣圖以發四聲", adapted.contains("氣圖"))
        assertTrue("企鵝應轉換為氣鵝以發四聲", adapted.contains("氣鵝"))

        val qwenPrompt = TaiwanesePromptAdapter.buildQwen3Prompt(input)
        assertTrue("Qwen簡體表示中應為气业", qwenPrompt.contains("气业"))
        assertTrue("Qwen簡體表示中應為气划", qwenPrompt.contains("气划"))
        assertTrue("Qwen簡體表示中應為气图", qwenPrompt.contains("气图"))
        assertTrue("Qwen簡體表示中應為气鹅", qwenPrompt.contains("气鹅"))
    }

    @Test
    fun testZhiAlwaysSecondTone() {
        // 質：在台灣一律讀二聲 zhí (直)
        val input = "這項產品的品質優良，具有特殊特質與實質價值，不容質疑。"
        val adapted = TaiwanesePromptAdapter.adapt(input)
        assertTrue("品質應轉為品直", adapted.contains("品直"))
        assertTrue("特質應轉為特直", adapted.contains("特直"))
        assertTrue("實質應轉為實直", adapted.contains("實直"))
        assertTrue("質疑應轉為直疑", adapted.contains("直疑"))

        val qwenPrompt = TaiwanesePromptAdapter.buildQwen3Prompt(input)
        assertTrue("Qwen簡體表示中應為品直", qwenPrompt.contains("品直"))
        assertTrue("Qwen簡體表示中應為特直", qwenPrompt.contains("特直"))
        assertTrue("Qwen簡體表示中應為直疑", qwenPrompt.contains("直疑"))
    }

    @Test
    fun testDangAlwaysThirdTone() {
        // 檔：在台灣一律讀三聲 dǎng (黨)
        val input = "請把這份檔案存檔，建立新的備份檔案。"
        val adapted = TaiwanesePromptAdapter.adapt(input)
        assertTrue("檔案應轉為黨案", adapted.contains("黨案"))
        assertTrue("存檔應轉為存黨", adapted.contains("存黨"))

        val qwenPrompt = TaiwanesePromptAdapter.buildQwen3Prompt(input)
        assertTrue("Qwen簡體表示中應為党案", qwenPrompt.contains("党案"))
        assertTrue("Qwen簡體表示中應為存党", qwenPrompt.contains("存党"))
    }

    @Test
    fun testXiaoAndYiPhonetics() {
        // 酵 (xiào -> 效), 液 (yì -> 意)
        val input = "這瓶發酵液體富含天然酵素與微量血液。"
        val adapted = TaiwanesePromptAdapter.adapt(input)
        assertTrue("發酵應轉為發效", adapted.contains("發效"))
        assertTrue("液體應轉為意體", adapted.contains("意體"))
        assertTrue("酵素應轉為效素", adapted.contains("效素"))
        assertTrue("血液應轉為血意", adapted.contains("血意"))
    }

    @Test
    fun testZhanAndGuaPhonetics() {
        // 暫 (zhàn -> 佔), 蝸 (guā -> 瓜), 拙 (zhuó -> 濁)
        val input = "暫時暫停運作，看著樹上的蝸牛，手法雖略顯笨拙。"
        val adapted = TaiwanesePromptAdapter.adapt(input)
        assertTrue("暫時應轉為佔時", adapted.contains("佔時"))
        assertTrue("暫停應轉為佔停", adapted.contains("佔停"))
        assertTrue("蝸牛應轉為瓜牛", adapted.contains("瓜牛"))
        assertTrue("笨拙應轉為笨濁", adapted.contains("笨濁"))
    }

    @Test
    fun testCrossStraitPhoneticWords() {
        // 垃圾 (lè sè), 給予 (jǐ yǔ), 洞穴 (dòng xiè), 微風 (wéi fēng), 堤防 (tí fáng), 攜帶 (xī dài)
        val input = "垃圾請隨手攜帶，給予大自然尊重，漫步在堤防微風中探索神秘洞穴。"
        val adapted = TaiwanesePromptAdapter.adapt(input)
        assertTrue("垃圾應轉為勒色", adapted.contains("勒色"))
        assertTrue("攜帶應轉為西帶", adapted.contains("西帶"))
        assertTrue("給予應轉為幾與", adapted.contains("幾與"))
        assertTrue("堤防應轉為提防", adapted.contains("提防"))
        assertTrue("微風應轉為維風", adapted.contains("維風"))
        assertTrue("洞穴應轉為洞謝", adapted.contains("洞謝"))
    }

    @Test
    fun testErhuaRemovalAndModalParticles() {
        val input = "早上好！沒事兒，我們去哪兒玩兒？好的。"
        val adapted = TaiwanesePromptAdapter.adapt(input)
        assertTrue("早上好應轉為早安", adapted.contains("早安"))
        assertTrue("沒事兒應轉為沒事啦", adapted.contains("沒事啦"))
        assertTrue("哪兒應轉為哪裡", adapted.contains("哪裡"))
        assertTrue("好的應轉為好喔～", adapted.contains("好喔～"))
    }
}
