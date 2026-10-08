package com.ping.verbead.ocr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PpOcrOrientationTest {

    @Test
    fun testNormalUprightTextNeverFlipped() {
        // 一般正常正向中文字串，0 度高置信度，絕對不翻轉（零退步保證）
        val adopt = PpOcrEngine.shouldAdopt180Rotation(
            text0 = "國立臺灣大學",
            conf0 = 0.92f,
            text180 = "冫氵",
            conf180 = 0.22f
        )
        assertFalse(adopt)
    }

    @Test
    fun testSymmetricNumberNineNeverFlippedToSix() {
        // 單個數字 9 在正向時置信度高，即便 180 度認出 6 且置信度略高，也絕不翻轉（防止對稱字倒置）
        val adopt = PpOcrEngine.shouldAdopt180Rotation(
            text0 = "9",
            conf0 = 0.93f,
            text180 = "6",
            conf180 = 0.96f
        )
        assertFalse(adopt)
    }

    @Test
    fun testSymmetricNumberSixNeverFlippedToNine() {
        // 單個數字 6 絕不翻轉為 9
        val adopt = PpOcrEngine.shouldAdopt180Rotation(
            text0 = "6",
            conf0 = 0.94f,
            text180 = "9",
            conf180 = 0.95f
        )
        assertFalse(adopt)
    }

    @Test
    fun testSymmetricWordNoNeverFlippedToOn() {
        // 英文單詞 no 絕不翻轉為 on
        val adopt = PpOcrEngine.shouldAdopt180Rotation(
            text0 = "no",
            conf0 = 0.91f,
            text180 = "on",
            conf180 = 0.93f
        )
        assertFalse(adopt)
    }

    @Test
    fun testTrueUpsideDownTextAdopted() {
        // 真實倒立文字：0 度為亂碼且置信度極低，180 度為清晰文字且置信度高 -> 成功採納 180 度
        val adopt = PpOcrEngine.shouldAdopt180Rotation(
            text0 = "冫氵",
            conf0 = 0.28f,
            text180 = "國立臺灣大學",
            conf180 = 0.93f
        )
        assertTrue(adopt)
    }

    @Test
    fun testEmptyZeroDegreeAdoptedWhen180HasValidCharacters() {
        // 0 度為空字串或純無效標點，180 度辨識出有效中文 -> 採納 180 度
        val adopt = PpOcrEngine.shouldAdopt180Rotation(
            text0 = "",
            conf0 = 0.0f,
            text180 = "統一發票號碼",
            conf180 = 0.88f
        )
        assertTrue(adopt)
    }

    @Test
    fun testNoisyBackgroundNeverFlipped() {
        // 背景雜訊雙方置信度皆低且無有效字元 -> 嚴格不翻轉
        val adopt = PpOcrEngine.shouldAdopt180Rotation(
            text0 = "...---",
            conf0 = 0.30f,
            text180 = ",,,---",
            conf180 = 0.35f
        )
        assertFalse(adopt)
    }

    @Test
    fun testBorderlineCaseKeepsUprightPrior() {
        // 臨界案例：雙方皆有文字且置信度相近時，強烈偏向 0 度（正向優先原則）
        val adopt = PpOcrEngine.shouldAdopt180Rotation(
            text0 = "有效文字",
            conf0 = 0.70f,
            text180 = "其他文字",
            conf180 = 0.73f
        )
        assertFalse(adopt)
    }

    @Test
    fun testUpsideDownChineseWithHallucinatedZeroDegreeChars() {
        // 倒立中文常見場景：0 度因部分筆畫誤識出 2 個字且置信度偏高（0.76），
        // 180 度完整辨識出 4 個正向漢字（0.89）-> 成功採納 180 度！
        val adopt = PpOcrEngine.shouldAdopt180Rotation(
            text0 = "十口",
            conf0 = 0.76f,
            text180 = "生活品質",
            conf180 = 0.89f
        )
        assertTrue(adopt)
    }

    @Test
    fun testUpsideDownTwoCharacterChineseWord() {
        // 倒立兩字中文字詞：0 度辨識出 2 個低信心字（0.55），
        // 180 度精確辨識出正確 2 字詞彙（0.88）-> 成功採納 180 度！
        val adopt = PpOcrEngine.shouldAdopt180Rotation(
            text0 = "十人",
            conf0 = 0.55f,
            text180 = "設定",
            conf180 = 0.88f
        )
        assertTrue(adopt)
    }

    @Test
    fun testUpsideDownThreeCharacterChinesePhrase() {
        // 倒立三字中文：0 度僅認出 2 個字（0.62），180 度完整認出 3 個字（0.86）-> 成功採納 180 度！
        val adopt = PpOcrEngine.shouldAdopt180Rotation(
            text0 = "一口",
            conf0 = 0.62f,
            text180 = "請輸入",
            conf180 = 0.86f
        )
        assertTrue(adopt)
    }

    @Test
    fun testUpsideDownSingleChineseCharacter() {
        // 倒立單個中文字：0 度為模糊假字（0.45），180 度為高置信度真字（0.91）-> 成功採納 180 度！
        val adopt = PpOcrEngine.shouldAdopt180Rotation(
            text0 = "口",
            conf0 = 0.45f,
            text180 = "讚",
            conf180 = 0.91f
        )
        assertTrue(adopt)
    }

    @Test
    fun testUprightSingleChineseCharacterNeverFlipped() {
        // 正向單個中文字：0 度高置信度（0.91），絕對不翻轉
        val adopt = PpOcrEngine.shouldAdopt180Rotation(
            text0 = "讚",
            conf0 = 0.91f,
            text180 = "口",
            conf180 = 0.45f
        )
        assertFalse(adopt)
    }

    @Test
    fun testUprightSymmetricChineseCharacterNeverFlipped() {
        // 正向對稱中文字（如「口」）：雙方皆為「口」且置信度高，正向優先，絕不翻轉
        val adopt = PpOcrEngine.shouldAdopt180Rotation(
            text0 = "口",
            conf0 = 0.93f,
            text180 = "口",
            conf180 = 0.93f
        )
        assertFalse(adopt)
    }

    @Test
    fun testUpsideDownEnglishTextAdopted() {
        // 倒立英文單詞：0 度為符號雜訊（0.25），180 度為清晰單詞（0.92）-> 成功採納 180 度！
        val adopt = PpOcrEngine.shouldAdopt180Rotation(
            text0 = "-.",
            conf0 = 0.25f,
            text180 = "CANCEL",
            conf180 = 0.92f
        )
        assertTrue(adopt)
    }
}

