package com.ping.verbead.tts

import org.junit.Assert.assertEquals
import org.junit.Test

class LanguageGuessTest {
    @Test fun hanOnlyIsChinese() = assertEquals(SpeechLang.ZH, LanguageGuess.of("今天天氣很好"))
    @Test fun kanaMeansJapanese() = assertEquals(SpeechLang.JA, LanguageGuess.of("今日はいい天気ですね"))
    @Test fun hangulMeansKorean() = assertEquals(SpeechLang.KO, LanguageGuess.of("오늘 날씨가 좋네요"))
    @Test fun latinIsOther() = assertEquals(SpeechLang.OTHER, LanguageGuess.of("The quick brown fox"))
    @Test fun digitsAndPunctuationAreOther() = assertEquals(SpeechLang.OTHER, LanguageGuess.of("123 ... ?!"))
    @Test fun mostlyEnglishWithOneChineseWordIsOther() =
        assertEquals(SpeechLang.OTHER, LanguageGuess.of("Please open the 設定 page and restart the application now"))
    @Test fun chineseWithAnEnglishAcronymIsChinese() =
        assertEquals(SpeechLang.ZH, LanguageGuess.of("請把 GPU 驅動程式更新到最新版本"))
}
