package com.ping.verbead.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrFallbackPolicyTest {

    private fun item(l: Int, t: Int, r: Int, b: Int) = ScreenTextItem(l, t, r, b, "x")

    @Test
    fun noTextNeedsOcr() {
        assertTrue(OcrFallbackPolicy.needsOcr(0, 0f))
        assertTrue(OcrFallbackPolicy.needsOcr(OcrFallbackPolicy.MIN_CHARS - 1, 0.9f))
    }

    @Test
    fun aFullPageOfTextDoesNot() {
        // measured: a web page, 422 characters over 96% of the screen
        assertFalse(OcrFallbackPolicy.needsOcr(422, 0.96f))
        // measured: a contacts screen, 256 characters over 56%
        assertFalse(OcrFallbackPolicy.needsOcr(256, 0.56f))
    }

    @Test
    fun littleTextOnLittleOfTheScreenNeedsOcr() {
        assertTrue(OcrFallbackPolicy.needsOcr(60, 0.05f))   // captions on a picture
        assertFalse(OcrFallbackPolicy.needsOcr(60, 0.4f))   // a short note, but it fills the screen
        assertFalse(OcrFallbackPolicy.needsOcr(500, 0.05f)) // plenty of text, however it is spread
    }

    @Test
    fun coverageOfNothingIsZero() {
        assertEquals(0f, OcrFallbackPolicy.coverage(emptyList(), 1000, 2000), 0f)
        assertEquals(0f, OcrFallbackPolicy.coverage(listOf(item(0, 0, 10, 10)), 0, 0), 0f)
    }

    @Test
    fun coverageOfTheWholeScreenIsOne() {
        assertEquals(1f, OcrFallbackPolicy.coverage(listOf(item(0, 0, 1000, 2000)), 1000, 2000), 0.001f)
    }

    @Test
    fun coverageOfAStripIsItsShareOfTheScreen() {
        // the top fifth of the screen, full width
        val c = OcrFallbackPolicy.coverage(listOf(item(0, 0, 1000, 400)), 1000, 2000)
        assertTrue("was $c", c in 0.18f..0.24f)
    }

    @Test
    fun overlappingItemsAreCountedOnce() {
        val one = OcrFallbackPolicy.coverage(listOf(item(0, 0, 500, 500)), 1000, 2000)
        val two = OcrFallbackPolicy.coverage(listOf(item(0, 0, 500, 500), item(0, 0, 500, 500)), 1000, 2000)
        assertEquals(one, two, 0f)
    }

    @Test
    fun itemsOutsideTheScreenDoNotCrash() {
        val c = OcrFallbackPolicy.coverage(listOf(item(-50, -50, 5000, 9000)), 1000, 2000)
        assertEquals(1f, c, 0.001f)
    }
}
