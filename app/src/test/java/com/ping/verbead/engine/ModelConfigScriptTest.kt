package com.ping.verbead.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelConfigScriptTest {

    @Test
    fun japaneseWithKana_isDetected() {
        assertTrue(ModelConfig.isJapaneseOrKorean("今日はいい天気ですね。"))
        assertTrue(ModelConfig.isJapaneseOrKorean("コーヒーをください"))
        assertTrue(ModelConfig.isJapaneseOrKorean("ｱｲｳ")) // 半形片假名
    }

    @Test
    fun koreanHangul_isDetected() {
        assertTrue(ModelConfig.isJapaneseOrKorean("안녕하세요, 만나서 반갑습니다."))
        assertTrue(ModelConfig.isJapaneseOrKorean("한국 사람"))
    }

    @Test
    fun chineseAndOthers_areNotJapaneseOrKorean() {
        assertFalse(ModelConfig.isJapaneseOrKorean(""))
        assertFalse(ModelConfig.isJapaneseOrKorean("今天天氣很好"))
        assertFalse(ModelConfig.isJapaneseOrKorean("Hello, world 123"))
        // 只有漢字的短句無從分辨，視為中文
        assertFalse(ModelConfig.isJapaneseOrKorean("東京"))
    }

    @Test
    fun anyKanaOrHangul_marksTheWholeTextAsJapaneseOrKorean() {
        // 不看比例：只要出現一個假名或諺文就算
        assertTrue(ModelConfig.isJapaneseOrKorean("他剛剛在會議上用日語說了一聲あ，然後就離開了房間去拿東西"))
        assertTrue(ModelConfig.isJapaneseOrKorean("這句話最後有個한"))
    }

    @Test
    fun japaneseText_isLeftUntouchedByTraditionalConversion() {
        val ja = "国の学校で会議があります。"
        assertEquals(ja, ModelConfig.toTaiwanTraditional(ja))
        assertEquals(ja, ModelConfig.normalizeTaiwanVariants(ja))
        assertEquals(ja, ModelConfig.applyPostRegexReplacements(ja))
    }

    @Test
    fun kanaOnlyAffectsItsOwnClause_chineseClausesAreStillConverted() {
        // 中文句有「只有中文才用」的字（这 专 线），整句照常轉；あ 所在的小句不受影響
        assertEquals(
            "這個專案要上線，大家辛苦了，あ",
            ModelConfig.toTaiwanTraditional("这个专案要上线，大家辛苦了，あ"),
        )
        // 順序相反也一樣
        assertEquals(
            "今日はいい天気ですね。這個專案要上線",
            ModelConfig.toTaiwanTraditional("今日はいい天気ですね。这个专案要上线"),
        )
    }

    @Test
    fun sharedKanjiWithChineseEvidence_isConverted() {
        // 「国」「学」是中日共用字，但同一小句有 这、远 這類中文證據，所以轉；日文句不動
        assertEquals(
            "這個國家的學校很遠。国の学校です。",
            ModelConfig.toTaiwanTraditional("这个国家的学校很远。国の学校です。"),
        )
    }

    @Test
    fun sharedKanjiOnly_inMixedText_isLeftAlone() {
        // 沒有任何中文證據、整段又有假名：寧可少轉，也不誤改日文
        val text = "国家的学校。国の学校です。"
        assertEquals(text, ModelConfig.toTaiwanTraditional(text))
    }

    @Test
    fun sharedKanjiOnly_inPureChineseText_isConverted() {
        // 整段沒有假名／諺文：共用字照常轉
        assertEquals("國家的學校", ModelConfig.toTaiwanTraditional("国家的学校"))
    }

    @Test
    fun chineseOnlyCharsAreConvertedEvenInsideAJapaneseClause() {
        // 同一小句夾雜：只有中文才用的「说」會轉，沒有共用字被誤改
        assertEquals("我說ありがとう", ModelConfig.toTaiwanTraditional("我说ありがとう"))
    }

    @Test
    fun koreanClause_isLeftAlone_chineseClauseConverted() {
        assertEquals(
            "學校很遠，안녕하세요 반갑습니다",
            ModelConfig.toTaiwanTraditional("学校很远，안녕하세요 반갑습니다"),
        )
    }

    @Test
    fun clauseSplitting_keepsTextLossless() {
        // 沒有任何可轉換內容時，切句再合併必須和原文完全相同
        val text = "Hello, world. 1.5 版本! 完了？\n\nOK;done:"
        assertEquals(text, ModelConfig.normalizeTaiwanVariants(text))
        assertEquals(text, ModelConfig.applyPostRegexReplacements(text))
    }

    @Test
    fun punctuationFilter_appliesToAnyLanguage() {
        // 標點過濾是使用者的設定，不做語言判定
        assertEquals("今日はいい天気です行こう", ModelConfig.filterChinesePunctuation("今日は、いい天気です。「行こう」"))
        assertEquals("안녕하세요 반갑습니다", ModelConfig.filterChinesePunctuation("안녕하세요… 반갑습니다。"))
    }

    @Test
    fun chineseText_isStillConverted() {
        // 簡體 → 繁體照常運作
        assertEquals("國家的學校", ModelConfig.toTaiwanTraditional("国家的学校"))
        // 中文標點照常過濾
        assertEquals("你好今天天氣很好", ModelConfig.filterChinesePunctuation("你好，今天天氣很好。"))
        assertNotEquals("国家的学校", ModelConfig.toTaiwanTraditional("国家的学校"))
    }
}
