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
}
