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
        assertEquals(4, CapsuleMenuController.modeToIndex(CapsuleMenuController.MODE_SLM))
        // Default fallback
        assertEquals(0, CapsuleMenuController.modeToIndex(999))
    }

    @Test
    fun testIndexToModeMapping() {
        assertEquals(CapsuleMenuController.MODE_VOICE, CapsuleMenuController.indexToMode(0))
        assertEquals(CapsuleMenuController.MODE_BARCODE, CapsuleMenuController.indexToMode(1))
        assertEquals(CapsuleMenuController.MODE_OCR, CapsuleMenuController.indexToMode(2))
        assertEquals(CapsuleMenuController.MODE_PHRASES, CapsuleMenuController.indexToMode(3))
        assertEquals(CapsuleMenuController.MODE_SLM, CapsuleMenuController.indexToMode(4))
        // Default fallback
        assertEquals(CapsuleMenuController.MODE_VOICE, CapsuleMenuController.indexToMode(-1))
        assertEquals(CapsuleMenuController.MODE_VOICE, CapsuleMenuController.indexToMode(999))
    }

    @Test
    fun testItemCount() {
        assertEquals(5, CapsuleMenuController.ITEM_COUNT)
    }

    @Test
    fun testCapsuleDimensionsAndExtent() {
        assertEquals(280, CapsuleMenuController.CAPSULE_MENU_HEIGHT_DP)
        assertEquals(304, CapsuleMenuController.CAPSULE_WINDOW_HEIGHT_DP)
        assertEquals(234, CapsuleMenuController.CAPSULE_MENU_MAX_EXTENT_DP)

        // Verify upward and downward extent symmetry
        // Window height = 304dp, bubble radius/half = 30dp
        // SLM (index 4) center: 12 + 28 + 4 * 56 = 264dp. Upward extent = 264 - 30 = 234dp.
        // Voice (index 0) center: 12 + 28 = 40dp. Downward extent = (304 - 40) - 30 = 234dp.
        val itemSlmCenterY = 12 + 28 + 4 * CapsuleMenuController.ITEM_HEIGHT_DP
        val upwardExtent = itemSlmCenterY - 30
        assertEquals(CapsuleMenuController.CAPSULE_MENU_MAX_EXTENT_DP, upwardExtent)

        val itemVoiceCenterY = 12 + 28
        val downwardExtent = (CapsuleMenuController.CAPSULE_WINDOW_HEIGHT_DP - itemVoiceCenterY) - 30
        assertEquals(CapsuleMenuController.CAPSULE_MENU_MAX_EXTENT_DP, downwardExtent)
    }

    @Test
    fun testCustomModeOrderAndDynamicEnableMapping() {
        val customOrder = com.ping.verbead.engine.ModelConfig.parseModeOrder("4,1,0")
        // Missing modes (2, 3) should be appended automatically in default order
        assertEquals(
            listOf(
                CapsuleMenuController.MODE_SLM,
                CapsuleMenuController.MODE_OCR,
                CapsuleMenuController.MODE_VOICE,
                CapsuleMenuController.MODE_BARCODE,
                CapsuleMenuController.MODE_PHRASES
            ),
            customOrder
        )

        // Reorder via moveModeInOrder
        val movedOrder = com.ping.verbead.engine.ModelConfig.moveModeInOrder(customOrder, 0, 2)
        assertEquals(
            listOf(
                CapsuleMenuController.MODE_OCR,
                CapsuleMenuController.MODE_VOICE,
                CapsuleMenuController.MODE_SLM,
                CapsuleMenuController.MODE_BARCODE,
                CapsuleMenuController.MODE_PHRASES
            ),
            movedOrder
        )

        // Filter disabled modes
        val disabled = com.ping.verbead.engine.ModelConfig.parseDisabledModes("2,3", movedOrder)
        val enabledModes = com.ping.verbead.engine.ModelConfig.computeEnabledModes(movedOrder, disabled)
        assertEquals(
            listOf(
                CapsuleMenuController.MODE_OCR,
                CapsuleMenuController.MODE_VOICE,
                CapsuleMenuController.MODE_SLM
            ),
            enabledModes
        )

        // CapsuleMenuController dynamic mapping for the 3 enabled modes
        assertEquals(0, CapsuleMenuController.modeToIndex(CapsuleMenuController.MODE_OCR, enabledModes))
        assertEquals(1, CapsuleMenuController.modeToIndex(CapsuleMenuController.MODE_VOICE, enabledModes))
        assertEquals(2, CapsuleMenuController.modeToIndex(CapsuleMenuController.MODE_SLM, enabledModes))
        assertEquals(CapsuleMenuController.MODE_SLM, CapsuleMenuController.indexToMode(2, enabledModes))
        assertEquals(3 * 56 + 24, CapsuleMenuController.computeCapsuleWindowHeightDp(enabledModes.size))

        // Guardrail: disabling all modes must preserve at least the first mode in currentOrder
        val allDisabledAttempt = com.ping.verbead.engine.ModelConfig.parseDisabledModes("0,1,2,3,4", movedOrder)
        val fallbackEnabled = com.ping.verbead.engine.ModelConfig.computeEnabledModes(movedOrder, allDisabledAttempt)
        assertEquals(listOf(CapsuleMenuController.MODE_OCR), fallbackEnabled)
    }
}
