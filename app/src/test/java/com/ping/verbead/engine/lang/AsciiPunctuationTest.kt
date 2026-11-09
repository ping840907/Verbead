package com.ping.verbead.engine.lang

import org.junit.Assert.assertEquals
import org.junit.Test

class AsciiPunctuationTest {
    private fun f(s: String) = AsciiPunctuation.filter(s)

    @Test fun removesCommonMarks() {
        assertEquals("Hello world", f("Hello, world."))
        assertEquals("Is this ok", f("Is this ok?"))
        assertEquals("She said hello", f("She said \"hello\"!"))
        assertEquals("one two three", f("one; two: three"))
        assertEquals("a b", f("(a) [b]"))
    }

    @Test fun keepsNumbersAndTimes() {
        assertEquals("pi is 3.14", f("pi is 3.14."))
        assertEquals("cost 1,000 dollars", f("cost 1,000 dollars,"))
        assertEquals("at 10:30 sharp", f("at 10:30, sharp"))
    }

    @Test fun keepsApostrophesHyphensAndSymbols() {
        assertEquals("don't stop well-known 50% a/b", f("don't stop, well-known 50% a/b."))
    }

    @Test fun leavesOtherTextAlone() {
        assertEquals("", f(""))
        assertEquals("plain text", f("plain text"))
        assertEquals("\u4e2d\u6587\u4e0d\u52d5", f("\u4e2d\u6587\u4e0d\u52d5"))
    }

    @Test fun profileDelegates() {
        assertEquals("ok", ZhTwProfile.filterHalfWidthPunctuation("ok!"))
    }
}
