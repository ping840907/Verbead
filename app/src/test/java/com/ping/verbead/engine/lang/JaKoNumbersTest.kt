package com.ping.verbead.engine.lang

import org.junit.Assert.assertEquals
import org.junit.Test

class JaKoNumbersTest {
    private fun ja(s: String) = JapaneseNumbers.convert(s)
    private fun ko(s: String) = KoreanNumbers.convert(s)

    @Test fun japaneseMagnitudes() {
        assertEquals("2025年", ja("二千二十五年"))
        assertEquals("10000円", ja("一万円"))
        assertEquals("100円", ja("百円"))
        assertEquals("350000000", ja("三億五千万"))
    }

    @Test fun japaneseDigitStringsAndDecimals() {
        assertEquals("2025年", ja("二〇二五年"))
        assertEquals("3.14", ja("三点一四"))
        assertEquals("3月", ja("三月"))
    }

    @Test fun japaneseIdiomsKept() {
        assertEquals("十分です", ja("十分です"))
        assertEquals("一番好き", ja("一番好き"))
        assertEquals("一緒に行く", ja("一緒に行く"))
        assertEquals("万が一の場合", ja("万が一の場合"))
        assertEquals("今日はいい天気ですね", ja("今日はいい天気ですね"))
    }

    @Test fun koreanSino() {
        assertEquals("23", ko("이십삼"))
        assertEquals("300명", ko("삼백 명").replace(" ", ""))
        assertEquals("2025년", ko("이천이십오 년").replace(" ", ""))
        assertEquals("10000원", ko("만 원").replace(" ", ""))
        assertEquals("50퍼센트", ko("오십 퍼센트").replace(" ", ""))
    }

    @Test fun koreanWordsKept() {
        assertEquals("이 사람은 오고 있다", ko("이 사람은 오고 있다"))
        assertEquals("사이가 좋다", ko("사이가 좋다"))
        assertEquals("오십견", ko("오십견"))
    }

    @Test fun koreanNative() {
        assertEquals("21살", ko("스물한 살").replace(" ", ""))
        assertEquals("3개", ko("세 개").replace(" ", ""))
        assertEquals("12시", ko("열두 시").replace(" ", ""))
        assertEquals("네, 알겠습니다", ko("네, 알겠습니다"))
    }

    @Test fun koreanDecimal() {
        assertEquals("0.5", ko("영 점 오"))
        assertEquals("3.14", ko("삼 점 일사"))
    }

    @Test fun dispatcherPicksByScript() {
        assertEquals("12 apples", SpokenNumbers.convert("twelve apples"))
        assertEquals("2025年", SpokenNumbers.convert("二〇二五年"))
        assertEquals("21살", SpokenNumbers.convert("스물한 살").replace(" ", ""))
    }
}
