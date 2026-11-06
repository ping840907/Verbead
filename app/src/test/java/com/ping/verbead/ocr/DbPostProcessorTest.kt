package com.ping.verbead.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class DbPostProcessorTest {

    private fun map(w: Int, h: Int, fill: (Array<FloatArray>) -> Unit): Array<FloatArray> =
        Array(h) { FloatArray(w) }.also(fill)

    private fun rect(m: Array<FloatArray>, x0: Int, y0: Int, x1: Int, y1: Int, v: Float = 0.9f) {
        for (y in y0..y1) for (x in x0..x1) m[y][x] = v
    }

    @Test
    fun smallTextLineIsKept() {
        // A phone-screen text line shrunk to the detector resolution is only ~4px tall; it must not be dropped.
        val m = map(200, 100) { rect(it, 20, 30, 120, 33) }
        val boxes = DbPostProcessor.extractBoxes(m, 200, 100)
        // (RectF is a stub on the JVM, so only the count is asserted here)
        assertEquals(1, boxes.size)
    }

    @Test
    fun oneRowThickStrokeIsFoundEvenOnOddRows() {
        // The old step-2 sampling could miss components lying only on odd rows/columns.
        val m = map(100, 50) { rect(it, 11, 21, 60, 23) }
        assertEquals(1, DbPostProcessor.extractBoxes(m, 100, 50).size)
    }

    @Test
    fun separateLinesStaySeparate() {
        val m = map(200, 100) {
            rect(it, 10, 10, 150, 14)
            rect(it, 10, 40, 150, 44)
            rect(it, 10, 70, 150, 74)
        }
        assertEquals(3, DbPostProcessor.extractBoxes(m, 200, 100).size)
    }

    @Test
    fun specksAndLowConfidenceAreRejected() {
        val m = map(100, 100) {
            rect(it, 10, 10, 11, 11)                 // 2x2 speck, below the minimum side
            rect(it, 40, 40, 80, 50, v = 0.3f)       // above the pixel threshold but below the box score
        }
        assertEquals(0, DbPostProcessor.extractBoxes(m, 100, 100).size)
    }

}
