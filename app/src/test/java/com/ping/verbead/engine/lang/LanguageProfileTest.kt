package com.ping.verbead.engine.lang

import org.junit.Assert.assertEquals
import org.junit.Test

class LanguageProfileTest {

    @Test
    fun zhTw_fullConversion_matchesTheRules() {
        assertEquals("國家的學校", ZhTwProfile.convertScript("国家的学校", ScriptMode.FULL))
        assertEquals(ZhTwRules.toTaiwanTraditional("这个专案要上线"), ZhTwProfile.convertScript("这个专案要上线", ScriptMode.FULL))
    }

    @Test
    fun zhTw_variantsOnly_doesNotConvertSimplified() {
        assertEquals("拚命打拚", ZhTwProfile.convertScript("拼命打拼", ScriptMode.VARIANTS_ONLY))
        assertEquals("国家的学校", ZhTwProfile.convertScript("国家的学校", ScriptMode.VARIANTS_ONLY))
    }

    @Test
    fun zhTw_off_keepsTheRecognizedText() {
        assertEquals("国家的学校", ZhTwProfile.convertScript("国家的学校", ScriptMode.OFF))
    }

    @Test
    fun zhTw_variantRulesNumbersAndPunctuation() {
        assertEquals("回應", ZhTwProfile.applyVariantRules("迴應"))
        assertEquals("123", ZhTwProfile.convertSpokenNumbers("一百二十三"))
        assertEquals("你好今天天氣很好", ZhTwProfile.filterPunctuation("你好，今天天氣很好。"))
    }

    @Test
    fun zhTw_japaneseAndKoreanClausesAreNotConverted() {
        val ja = "今日はいい天気ですね。"
        assertEquals(ja, ZhTwProfile.convertScript(ja, ScriptMode.FULL))
        assertEquals(ja, ZhTwProfile.convertSpokenNumbers(ja))
        assertEquals("今日はいい天気です行こう", ZhTwProfile.filterPunctuation("今日は、いい天気です。「行こう」"))
        assertEquals("안녕하세요 반갑습니다", ZhTwProfile.filterPunctuation("안녕하세요… 반갑습니다。"))
    }
}
