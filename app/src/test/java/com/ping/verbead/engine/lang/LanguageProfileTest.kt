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

class TraditionalTextTest {
    @Test fun traditionalTextIsNotConvertedAgain() {
        // 干預 and 里長 are right in Traditional; s2t would turn them into 幹預 and 裡長
        assertEquals("他干預別人的事情，還說這樣不對", ZhTwRules.toTaiwanTraditional("他干預別人的事情，還說這樣不對"))
        assertEquals("公里數與里長", ZhTwRules.toTaiwanTraditional("公里數與里長"))
    }

    @Test fun simplifiedOnlyCharactersAreAlwaysConverted() {
        // Traditional text with some Simplified characters in it: those are converted, 干預 and 里長 stay
        assertEquals("他干預別人的事情，還說這樣不對，對嗎", ZhTwRules.toTaiwanTraditional("他干預別人的事情，還說這樣不对，对吗"))
        assertEquals("公里數與里長這個", ZhTwRules.toTaiwanTraditional("公里數與里長这个"))
    }

    @Test fun simplifiedTextIsStillConverted() {
        assertEquals("他干預別人的事情，還說這樣不對", ZhTwRules.toTaiwanTraditional("他干预别人的事情，还说这样不对"))
        assertEquals("國家的學校", ZhTwRules.toTaiwanTraditional("国家的学校"))
    }

    @Test fun fewTraditionalCharactersAmongSimplifiedOnesDoNotCount() {
        // 數 與 長 are Traditional, but the text is Simplified: it is converted
        val mixed = ZhTwRules.toTaiwanTraditional("这个项目的数據與長度还需要检查，请联络负责的人")
        assertEquals(true, mixed.contains("項目") && mixed.contains("檢查") && mixed.contains("聯絡"))
    }

    @Test fun evidenceNeedsThreeTraditionalOnlyCharacters() {
        assertEquals(false, ZhTwRules.isAlreadyTraditional("干預別人"))
        assertEquals(true, ZhTwRules.isAlreadyTraditional("干預別人的數"))
        assertEquals(false, ZhTwRules.isAlreadyTraditional("国家的学校"))
        assertEquals(false, ZhTwRules.isAlreadyTraditional(""))
    }

    @Test fun japaneseIsLeftAlone() {
        val ja = "国の学校で会議があります。"
        assertEquals(ja, ZhTwRules.toTaiwanTraditional(ja))
    }
}
