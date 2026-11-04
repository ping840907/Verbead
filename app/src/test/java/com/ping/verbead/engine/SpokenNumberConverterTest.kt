package com.ping.verbead.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class SpokenNumberConverterTest {

    private fun check(expected: String, input: String) =
        assertEquals("輸入：$input", expected, SpokenNumberConverter.convert(input))

    private fun unchanged(input: String) = check(input, input)

    @Test
    fun integers() {
        check("123", "一百二十三")
        check("103", "一百零三")
        check("1020", "一千零二十")
        check("25", "二十五")
        check("15個人", "十五個人")
        check("30005", "三萬零五")
        check("123000000", "一億兩千三百萬")
        check("12000", "一萬兩千")
        check("2025", "二零二五")
        check("0912345678", "零九一二三四五六七八")
    }

    @Test
    fun colloquialShorthand() {
        check("3500", "三千五")
        check("12000", "一萬二")
        check("250", "兩百五")
    }

    @Test
    fun singleDigits_onlyConvertedInExplicitNumberContext() {
        check("第3名", "第三名")
        check("第12名", "第十二名")
        // 多位數照常轉
        check("15個人", "十五個人")
        check("12月20號", "十二月二十號")
        check("2025年三月五日", "二零二五年三月五日")
    }

    @Test
    fun singleDigitsFollowedByAMeasureWord_areNotConverted() {
        unchanged("我有三個蘋果")
        unchanged("兩個人")
        unchanged("十分鐘")
        unchanged("一天")
        unchanged("五件事")
    }

    @Test
    fun ordinaryWordsAreNotTouched() {
        unchanged("等一下")
        unchanged("我們一起去")
        unchanged("統一發票")
        unchanged("萬一下雨")
        unchanged("他十分好")
        unchanged("十字路口")
        unchanged("千萬別忘了")
        unchanged("三兩肉")
        unchanged("二手車")
    }

    @Test
    fun idiomsAreNotTouched() {
        unchanged("他一天到晚都在睡覺")
        unchanged("三心二意")
        unchanged("一心一意")
        unchanged("七上八下")
        unchanged("九死一生")
        unchanged("三十而立")
    }

    @Test
    fun approximateAndCountingStrings_areNotTouched() {
        unchanged("大概三四個")
        unchanged("一二三四五")
        unchanged("三二一開始")
        unchanged("兩三天")
    }

    @Test
    fun arithmetic() {
        check("3+5=8", "三加五等於八")
        check("10-4", "十減四")
        check("6×7", "六乘七")
        check("8÷2", "八除以二")
        check("6×2", "六乘以二")
        check("1+1=2", "一加一等於二")
        check("1.5+2.5=4", "一點五加二點五等於四")
    }

    @Test
    fun operatorWordsInOrdinaryUseAreNotTouched() {
        unchanged("除了他以外")
        unchanged("加入我們")
        unchanged("三加入")
        unchanged("三減少")
        unchanged("我不等於他")
    }

    @Test
    fun percent() {
        check("50%", "五十趴")
        check("50%", "百分之五十")
        check("0.5%", "百分之零點五")
        check("100%", "百分之一百")
        check("3‰", "千分之三")
        check("10%+20%", "百分之十加百分之二十")
    }

    @Test
    fun fractions() {
        check("1/3", "三分之一")
        check("3/10", "十分之三")
        check("1/2", "二分之一")
    }

    @Test
    fun negativeAndRoot() {
        check("-3", "負三")
        check("-2.5", "負二點五")
        unchanged("他要負責任")
        check("√2", "根號二")
        check("2√3", "二根號三")
        check("√25", "根號二十五")
    }

    @Test
    fun point_decimal() {
        check("3.5", "三點五")
        check("0.05", "零點零五")
        check("圓周率是3.14159", "圓周率是三點一四一五九")
        check("2.0", "二點零")
        check("12.5", "十二點五")
    }

    @Test
    fun point_time() {
        check("3:15", "三點十五分")
        check("3:15", "三點十五")
        check("3:30", "三點半")
        check("3:00", "三點整")
        check("3:00", "三點鐘")
        check("3:20", "三點二十分")
        check("3:05", "三點零五分")
        check("3:05", "三點五分")
        check("3:15", "三點一刻")
        check("3:45", "三點三刻")
        check("8:35:20", "八點三十五分二十秒")
    }

    @Test
    fun point_timeCues() {
        check("下午3:15", "下午三點十五")
        check("下午3:05", "下午三點五")
        check("晚上10:30", "晚上十點半")
        // 只有「H點」：單獨的一位數，不動
        unchanged("下午三點")
    }

    @Test
    fun point_otherUsesAreNotTouched() {
        unchanged("我有一點點意見")
        unchanged("給我一點意見")
        unchanged("我三點到")
        unchanged("這是重點")
    }

    @Test
    fun simplifiedChinese() {
        check("3+5=8", "三加五等于八")
        check("250", "两百五")
        check("-3", "负三")
        check("3:30", "三点半")
        check("12000", "一万两千")
        check("50%", "百分之五十")
        check("10-4", "十减四")
    }

    @Test
    fun japaneseAndKoreanClausesAreSkipped() {
        unchanged("今日は三日です")
        unchanged("안녕하세요 삼")
        check("我有25個蘋果。今日は三日です", "我有二十五個蘋果。今日は三日です")
    }

    @Test
    fun emptyAndPlainText() {
        check("", "")
        unchanged("Hello world 123")
        unchanged("沒有任何數字的句子")
    }
}
