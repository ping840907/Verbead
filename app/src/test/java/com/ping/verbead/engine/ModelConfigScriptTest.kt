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
    fun chineseQuotingAFewKana_staysChinese() {
        // 假名占比低於 20%：整句仍是中文，簡轉繁等處理要照常進行
        assertFalse(ModelConfig.isJapaneseOrKorean("他剛剛在會議上用日語說了一聲あ，然後就離開了房間去拿東西"))
    }

    @Test
    fun japaneseText_isLeftUntouchedByTraditionalConversion() {
        val ja = "国の学校で会議があります。"
        assertEquals(ja, ModelConfig.toTaiwanTraditional(ja))
        assertEquals(ja, ModelConfig.normalizeTaiwanVariants(ja))
        assertEquals(ja, ModelConfig.applyPostRegexReplacements(ja))
    }

    @Test
    fun japaneseAndKoreanPunctuation_isNotFiltered() {
        val ja = "今日は、いい天気です。「行こう」"
        assertEquals(ja, ModelConfig.filterChinesePunctuation(ja))
        val ko = "안녕하세요… 반갑습니다。"
        assertEquals(ko, ModelConfig.filterChinesePunctuation(ko))
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
