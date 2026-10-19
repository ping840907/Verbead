package com.ping.verbead.bubble

import com.ping.verbead.engine.ModelConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleShakeDuplicationLimitTest {

    @Test
    fun testResolveMaxMultiBubbleCount() {
        assertEquals(1, ModelConfig.resolveMaxMultiBubbleCount(0))
        assertEquals(1, ModelConfig.resolveMaxMultiBubbleCount(1))
        assertEquals(2, ModelConfig.resolveMaxMultiBubbleCount(2))
        assertEquals(3, ModelConfig.resolveMaxMultiBubbleCount(3))
        assertEquals(4, ModelConfig.resolveMaxMultiBubbleCount(4))
        assertEquals(5, ModelConfig.resolveMaxMultiBubbleCount(5))
        assertEquals(5, ModelConfig.resolveMaxMultiBubbleCount(6))
        assertEquals(5, ModelConfig.resolveMaxMultiBubbleCount(10))
    }

    @Test
    fun testDuplicationLogicWithSingleModeEnabled() {
        // When user only enabled 1 mode (e.g. Voice only)
        val enabledModes = listOf(ModelConfig.BUBBLE_MODE_VOICE)
        val maxAllowed = ModelConfig.resolveMaxMultiBubbleCount(enabledModes.size)
        assertEquals(1, maxAllowed)

        var bubbleCount = 1
        val canDuplicate = bubbleCount < maxAllowed
        assertFalse("Cannot duplicate when only 1 mode is enabled", canDuplicate)
    }

    @Test
    fun testDuplicationLogicWithThreeModesEnabled() {
        // User enabled 3 modes (e.g. Voice, Barcode, OCR)
        val enabledModes = listOf(
            ModelConfig.BUBBLE_MODE_VOICE,
            ModelConfig.BUBBLE_MODE_BARCODE,
            ModelConfig.BUBBLE_MODE_OCR
        )
        val maxAllowed = ModelConfig.resolveMaxMultiBubbleCount(enabledModes.size)
        assertEquals(3, maxAllowed)

        var currentBubbles = 1

        // 1st shake -> duplicates to 2
        assertTrue(currentBubbles < maxAllowed)
        currentBubbles++
        assertEquals(2, currentBubbles)

        // 2nd shake -> duplicates to 3
        assertTrue(currentBubbles < maxAllowed)
        currentBubbles++
        assertEquals(3, currentBubbles)

        // 3rd shake -> blocked because already at limit 3
        assertFalse(currentBubbles < maxAllowed)
    }

    @Test
    fun testDuplicationLogicWithAllFiveModesEnabled() {
        val enabledModes = ModelConfig.DEFAULT_BUBBLE_MODE_ORDER
        val maxAllowed = ModelConfig.resolveMaxMultiBubbleCount(enabledModes.size)
        assertEquals(5, maxAllowed)

        var currentBubbles = 1
        while (currentBubbles < maxAllowed) {
            currentBubbles++
        }
        assertEquals(5, currentBubbles)
        assertFalse("Further shake must be blocked when reaching 5", currentBubbles < maxAllowed)
    }
}
