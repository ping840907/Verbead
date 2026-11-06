package com.ping.verbead.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrShapesTest {
    @Test
    fun phoneScreenshotUsesThePortraitDetector() {
        val p = OcrShapes.planDet(1080, 2400)
        assertEquals(480, p.width); assertEquals(960, p.height)
        assertEquals(0.4f, p.scale, 1e-3f)
    }

    @Test
    fun landscapeScreenUsesTheLandscapeDetector() {
        val p = OcrShapes.planDet(2400, 1080)
        assertEquals(960, p.width); assertEquals(480, p.height)
    }

    @Test
    fun cameraFrameUsesTheSquareOrBetterFit() {
        val p = OcrShapes.planDet(1280, 960) // 4:3 landscape
        assertTrue(p.width >= 640)
        assertTrue(p.contentW <= p.width && p.contentH <= p.height)
    }

    @Test
    fun smallImagesAreOnlyEnlargedUpToTwoTimes() {
        val p = OcrShapes.planDet(100, 60)
        assertEquals(2.0f, p.scale, 1e-6f)
        assertEquals(200, p.contentW)
    }

    @Test
    fun recBucketsAndWidths() {
        assertEquals(32, OcrShapes.recStripWidth(10, 48))
        assertEquals(160, OcrShapes.recBucket(150))
        assertEquals(320, OcrShapes.recBucket(161))
        assertEquals(1280, OcrShapes.recBucket(5000))
        assertEquals(1280, OcrShapes.recBucket(5000, tiny = true))
        assertEquals(64, OcrShapes.recBucket(40, tiny = true))
        assertEquals(384, OcrShapes.recBucket(350, tiny = true))
        assertEquals(1280, OcrShapes.recStripWidth(100000, 48))
    }
}
