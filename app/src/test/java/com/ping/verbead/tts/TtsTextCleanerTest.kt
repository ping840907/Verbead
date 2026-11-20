package com.ping.verbead.tts

import org.junit.Assert.assertEquals
import org.junit.Test

class TtsTextCleanerTest {

    @Test
    fun removesUrls() {
        assertEquals("看這裡 很好", TtsTextCleaner.clean("看這裡 https://example.com/a?b=1 很好"))
        assertEquals("go", TtsTextCleaner.clean("www.example.com go"))
    }

    @Test
    fun removesEmojiButKeepsText() {
        assertEquals("好開心", TtsTextCleaner.clean("好開心😀🎉"))
        assertEquals("ok", TtsTextCleaner.clean("ok 👍🏽"))
        assertEquals("a b", TtsTextCleaner.clean("a ❤️ b"))
    }

    @Test
    fun removesInvisibleCharacters() {
        assertEquals("ab", TtsTextCleaner.clean("a​b﻿"))
    }

    @Test
    fun collapsesBlanksAndEmptyLinesButKeepsLineBreaks() {
        assertEquals("a b\nc", TtsTextCleaner.clean("a   b\n\n\n  c  "))
        assertEquals("a\nb", TtsTextCleaner.clean("a\r\nb"))
    }

    @Test
    fun keepsPunctuationAndCjk() {
        assertEquals("你好，世界。Hello!", TtsTextCleaner.clean("你好，世界。Hello!"))
    }

    @Test
    fun blankInputGivesEmpty() {
        assertEquals("", TtsTextCleaner.clean(" \n\t "))
    }

    @Test
    fun removesCitationMarks() {
        assertEquals("臺北市是直轄市，及首都。", TtsTextCleaner.clean("臺北市是直轄市[4]，及首都[註 2][5]。"))
        assertEquals("人口密度最高的建制市。", TtsTextCleaner.clean("人口密度最高[ 註 4 ]的建制市[ 3 ]。"))
        assertEquals("a note", TtsTextCleaner.clean("a[note 2] note[a]"))
        assertEquals("歷史", TtsTextCleaner.clean("歷史[編輯]"))
    }

    @Test
    fun aLineOfNothingButCitationMarksBecomesEmpty() {
        assertEquals("", TtsTextCleaner.clean("[註 3] [註 4] [3]"))
    }

    @Test
    fun otherBracketsAreKept() {
        assertEquals("請見[臺北]條目", TtsTextCleaner.clean("請見[臺北]條目"))
        assertEquals("範圍 [1, 2]", TtsTextCleaner.clean("範圍 [1, 2]"))
    }
}
