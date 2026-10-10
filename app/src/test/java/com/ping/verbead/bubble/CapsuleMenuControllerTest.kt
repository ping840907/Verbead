package com.ping.verbead.bubble

import org.junit.Assert.assertEquals
import org.junit.Test

class CapsuleMenuControllerTest {

    @Test
    fun testModeToIndexMapping() {
        assertEquals(0, CapsuleMenuController.modeToIndex(CapsuleMenuController.MODE_VOICE))
        assertEquals(1, CapsuleMenuController.modeToIndex(CapsuleMenuController.MODE_BARCODE))
        assertEquals(2, CapsuleMenuController.modeToIndex(CapsuleMenuController.MODE_OCR))
        assertEquals(3, CapsuleMenuController.modeToIndex(CapsuleMenuController.MODE_PHRASES))
        // Default fallback
        assertEquals(0, CapsuleMenuController.modeToIndex(999))
    }

    @Test
    fun testIndexToModeMapping() {
        assertEquals(CapsuleMenuController.MODE_VOICE, CapsuleMenuController.indexToMode(0))
        assertEquals(CapsuleMenuController.MODE_BARCODE, CapsuleMenuController.indexToMode(1))
        assertEquals(CapsuleMenuController.MODE_OCR, CapsuleMenuController.indexToMode(2))
        assertEquals(CapsuleMenuController.MODE_PHRASES, CapsuleMenuController.indexToMode(3))
        // Default fallback
        assertEquals(CapsuleMenuController.MODE_VOICE, CapsuleMenuController.indexToMode(-1))
        assertEquals(CapsuleMenuController.MODE_VOICE, CapsuleMenuController.indexToMode(999))
    }

    @Test
    fun testItemCount() {
        assertEquals(4, CapsuleMenuController.ITEM_COUNT)
    }
}
