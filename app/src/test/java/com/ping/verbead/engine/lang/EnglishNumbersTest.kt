package com.ping.verbead.engine.lang

import org.junit.Assert.assertEquals
import org.junit.Test

class EnglishNumbersTest {
    private fun c(s: String) = EnglishNumbers.convert(s)

    @Test fun largeNumbersConvert() {
        assertEquals("I paid 23 dollars", c("I paid twenty three dollars"))
        assertEquals("There are 105 people", c("There are one hundred and five people"))
        assertEquals("It costs 2025 yuan", c("It costs two thousand twenty five yuan"))
        assertEquals("12 apples", c("twelve apples"))
        assertEquals("a total of 1500", c("a total of one thousand five hundred"))
    }

    @Test fun singleDigitsStayWords() {
        assertEquals("I have two cats", c("I have two cats"))
        assertEquals("one of them", c("one of them"))
        assertEquals("no one knows", c("no one knows"))
    }

    @Test fun singleDigitWithUnitOrCalculation() {
        assertEquals("5 percent", c("five percent"))
        assertEquals("3 dollars", c("three dollars"))
        assertEquals("2 + 3 = 5", c("two plus three equals five"))
        assertEquals("3.5", c("three point five"))
    }

    @Test fun years() {
        assertEquals("in 2025", c("in twenty twenty five"))
        assertEquals("in 1984", c("in nineteen eighty four"))
    }

    @Test fun digitStrings() {
        assertEquals("call 5551234", c("call five five five one two three four"))
        assertEquals("one two three", c("one two three"))
    }

    @Test fun nothingToDo() {
        assertEquals("Hello world.", c("Hello world."))
        assertEquals("", c(""))
    }
}
