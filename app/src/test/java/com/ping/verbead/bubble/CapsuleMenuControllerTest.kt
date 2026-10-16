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

    @Test
    fun testCapsuleDimensionsAndExtent() {
        assertEquals(224, CapsuleMenuController.CAPSULE_MENU_HEIGHT_DP)
        assertEquals(248, CapsuleMenuController.CAPSULE_WINDOW_HEIGHT_DP)
        assertEquals(178, CapsuleMenuController.CAPSULE_MENU_MAX_EXTENT_DP)

        // Verify upward and downward extent symmetry
        // Window height = 248dp, bubble radius/half = 30dp
        // Phrases (index 3) center: 12 + 28 + 3 * 56 = 208dp. Upward extent = 208 - 30 = 178dp.
        // Voice (index 0) center: 12 + 28 = 40dp. Downward extent = (248 - 40) - 30 = 178dp.
        val itemPhrasesCenterY = 12 + 28 + 3 * CapsuleMenuController.ITEM_HEIGHT_DP
        val upwardExtent = itemPhrasesCenterY - 30
        assertEquals(CapsuleMenuController.CAPSULE_MENU_MAX_EXTENT_DP, upwardExtent)

        val itemVoiceCenterY = 12 + 28
        val downwardExtent = (CapsuleMenuController.CAPSULE_WINDOW_HEIGHT_DP - itemVoiceCenterY) - 30
        assertEquals(CapsuleMenuController.CAPSULE_MENU_MAX_EXTENT_DP, downwardExtent)
    }
}
