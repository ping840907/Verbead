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
        // Last item (index 3) center: 12 + 28 + 3 * 56 = 208dp. Upward extent = 208 - 30 = 178dp.
        // Voice (index 0) center: 12 + 28 = 40dp. Downward extent = (248 - 40) - 30 = 178dp.
        val lastItemCenterY = 12 + 28 + 3 * CapsuleMenuController.ITEM_HEIGHT_DP
        val upwardExtent = lastItemCenterY - 30
        assertEquals(CapsuleMenuController.CAPSULE_MENU_MAX_EXTENT_DP, upwardExtent)

        val itemVoiceCenterY = 12 + 28
        val downwardExtent = (CapsuleMenuController.CAPSULE_WINDOW_HEIGHT_DP - itemVoiceCenterY) - 30
        assertEquals(CapsuleMenuController.CAPSULE_MENU_MAX_EXTENT_DP, downwardExtent)
    }

    @Test
    fun testRemovedModeInStoredOrderIsIgnored() {
        // 舊版存下的排序可能含已移除的第 5 個模式（4）：要被濾掉，其餘缺少的依預設順序補上
        val order = com.ping.verbead.engine.ModelConfig.parseModeOrder("4,1,0")
        assertEquals(
            listOf(
                CapsuleMenuController.MODE_OCR,
                CapsuleMenuController.MODE_VOICE,
                CapsuleMenuController.MODE_BARCODE,
                CapsuleMenuController.MODE_PHRASES
            ),
            order
        )
        val disabled = com.ping.verbead.engine.ModelConfig.parseDisabledModes("4,2", order)
        assertEquals(setOf(CapsuleMenuController.MODE_BARCODE), disabled)
    }

    @Test
    fun testCustomModeOrderAndDynamicEnableMapping() {
        val customOrder = com.ping.verbead.engine.ModelConfig.parseModeOrder("3,1,0")
        // Missing mode (2) should be appended automatically in default order
        assertEquals(
            listOf(
                CapsuleMenuController.MODE_PHRASES,
                CapsuleMenuController.MODE_OCR,
                CapsuleMenuController.MODE_VOICE,
                CapsuleMenuController.MODE_BARCODE
            ),
            customOrder
        )

        // Reorder via moveModeInOrder
        val movedOrder = com.ping.verbead.engine.ModelConfig.moveModeInOrder(customOrder, 0, 2)
        assertEquals(
            listOf(
                CapsuleMenuController.MODE_OCR,
                CapsuleMenuController.MODE_VOICE,
                CapsuleMenuController.MODE_PHRASES,
                CapsuleMenuController.MODE_BARCODE
            ),
            movedOrder
        )

        // Filter disabled modes
        val disabled = com.ping.verbead.engine.ModelConfig.parseDisabledModes("2,3", movedOrder)
        val enabledModes = com.ping.verbead.engine.ModelConfig.computeEnabledModes(movedOrder, disabled)
        assertEquals(
            listOf(
                CapsuleMenuController.MODE_OCR,
                CapsuleMenuController.MODE_VOICE
            ),
            enabledModes
        )

        // CapsuleMenuController dynamic mapping for the 2 enabled modes
        assertEquals(0, CapsuleMenuController.modeToIndex(CapsuleMenuController.MODE_OCR, enabledModes))
        assertEquals(1, CapsuleMenuController.modeToIndex(CapsuleMenuController.MODE_VOICE, enabledModes))
        assertEquals(CapsuleMenuController.MODE_VOICE, CapsuleMenuController.indexToMode(1, enabledModes))
        assertEquals(2 * 56 + 24, CapsuleMenuController.computeCapsuleWindowHeightDp(enabledModes.size))

        // Guardrail: disabling all modes must preserve at least the first mode in currentOrder
        val allDisabledAttempt = com.ping.verbead.engine.ModelConfig.parseDisabledModes("0,1,2,3", movedOrder)
        val fallbackEnabled = com.ping.verbead.engine.ModelConfig.computeEnabledModes(movedOrder, allDisabledAttempt)
        assertEquals(listOf(CapsuleMenuController.MODE_OCR), fallbackEnabled)
    }
}
