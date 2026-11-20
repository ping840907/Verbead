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
}
