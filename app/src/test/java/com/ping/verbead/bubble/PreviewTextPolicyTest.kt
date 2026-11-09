package com.ping.verbead.bubble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewTextPolicyTest {

    @Test
    fun aStatusTextThatFitsOnTwoLinesWrapsInsteadOfScrolling() {
        assertFalse(PreviewTextPolicy.needsMarquee(textWidthPx = 300f, lineWidthPx = 400f))   // one line
        assertFalse(PreviewTextPolicy.needsMarquee(textWidthPx = 799f, lineWidthPx = 400f))   // two lines
        assertTrue(PreviewTextPolicy.needsMarquee(textWidthPx = 801f, lineWidthPx = 400f))    // would need three
    }

    @Test
    fun noMarqueeWithoutAUsableWidth() {
        assertFalse(PreviewTextPolicy.needsMarquee(textWidthPx = 5000f, lineWidthPx = 0f))
    }

    @Test
    fun scrollTimeFollowsTheHiddenLengthWithAFloor() {
        // 40 dp per second at density 2 = 80 px per second
        assertEquals(2000L, PreviewTextPolicy.marqueeDurationMs(overflowPx = 160f, density = 2f))
        assertEquals(600L, PreviewTextPolicy.marqueeDurationMs(overflowPx = 4f, density = 2f))
    }
}
