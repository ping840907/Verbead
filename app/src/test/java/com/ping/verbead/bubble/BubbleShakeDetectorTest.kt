package com.ping.verbead.bubble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleShakeDetectorTest {

    private val density = 2.0f // 1dp = 2px, minStrokePx = 32px

    @Test
    fun testHorizontalShakeTriggersDuplication() {
        var triggeredCount = 0
        val strokeProgressList = mutableListOf<Float>()
        val horizontalFlags = mutableListOf<Boolean>()

        val detector = BubbleShakeDetector(
            density = density,
            minStrokeDp = 16f,
            maxWindowMs = 650L,
            targetStrokes = 3,
            cooldownMs = 900L,
            onShakeStroke = { _, _, progress, isHorizontal ->
                strokeProgressList.add(progress)
                horizontalFlags.add(isHorizontal)
            },
            onShakeTriggered = {
                triggeredCount++
            }
        )

        var time = 1000L

        // Initial sample
        detector.addSample(100f, 100f, time)

        // Move right (+50px)
        time += 50L
        detector.addSample(150f, 100f, time)

        // Reverse left (-60px) -> Stroke 1
        time += 50L
        detector.addSample(90f, 100f, time)
        assertEquals(1, strokeProgressList.size)
        assertTrue(horizontalFlags.last())

        // Reverse right (+70px) -> Stroke 2
        time += 50L
        detector.addSample(160f, 100f, time)
        assertEquals(2, strokeProgressList.size)
        assertTrue(horizontalFlags.last())

        // Reverse left (-70px) -> Stroke 3 -> Trigger!
        time += 50L
        detector.addSample(90f, 100f, time)
        assertEquals(3, strokeProgressList.size)
        assertEquals(1, triggeredCount)
        assertFalse(detector.isShaking)
    }

    @Test
    fun testVerticalShakeTriggersDuplication() {
        var triggeredCount = 0
        val horizontalFlags = mutableListOf<Boolean>()

        val detector = BubbleShakeDetector(
            density = density,
            minStrokeDp = 16f,
            maxWindowMs = 650L,
            targetStrokes = 3,
            cooldownMs = 900L,
            onShakeStroke = { _, _, _, isHorizontal ->
                horizontalFlags.add(isHorizontal)
            },
            onShakeTriggered = {
                triggeredCount++
            }
        )

        var time = 1000L
        detector.addSample(100f, 100f, time)

        // Move down (+50px)
        time += 50L
        detector.addSample(100f, 150f, time)

        // Reverse up (-60px) -> Stroke 1
        time += 50L
        detector.addSample(100f, 90f, time)
        assertFalse(horizontalFlags.last())

        // Reverse down (+70px) -> Stroke 2
        time += 50L
        detector.addSample(100f, 160f, time)
        assertFalse(horizontalFlags.last())

        // Reverse up (-70px) -> Stroke 3 -> Trigger!
        time += 50L
        detector.addSample(100f, 90f, time)
        assertEquals(1, triggeredCount)
        assertFalse(horizontalFlags.last())
    }

    @Test
    fun testSlowMovementsDoNotTriggerDueToSlidingWindowExpiry() {
        var triggered = false

        val detector = BubbleShakeDetector(
            density = density,
            minStrokeDp = 16f,
            maxWindowMs = 300L, // 300ms tight window
            targetStrokes = 3,
            onShakeTriggered = { triggered = true }
        )

        var time = 1000L
        detector.addSample(100f, 100f, time)

        // Stroke 1
        time += 50L
        detector.addSample(150f, 100f, time)
        time += 50L
        detector.addSample(90f, 100f, time)

        // Stroke 2
        time += 50L
        detector.addSample(160f, 100f, time)

        // Delay 400ms (> maxWindowMs 300ms) - older strokes should expire
        time += 400L
        detector.addSample(90f, 100f, time) // this reversal happens, but older ones were pruned

        assertFalse(triggered)
    }

    @Test
    fun testSubThresholdMovementsIgnored() {
        var triggered = false
        var strokes = 0

        val detector = BubbleShakeDetector(
            density = density,
            minStrokeDp = 16f, // 32px
            targetStrokes = 3,
            onShakeStroke = { _, _, _, _ -> strokes++ },
            onShakeTriggered = { triggered = true }
        )

        var time = 1000L
        detector.addSample(100f, 100f, time)

        // Oscillate back and forth by only 5px (< 32px)
        for (i in 0 until 10) {
            time += 50L
            val x = if (i % 2 == 0) 105f else 95f
            detector.addSample(x, 100f, time)
        }

        assertEquals(0, strokes)
        assertFalse(triggered)
    }

    @Test
    fun testCooldownSuppressesImmediateRetrigger() {
        var triggerCount = 0

        val detector = BubbleShakeDetector(
            density = density,
            minStrokeDp = 16f,
            targetStrokes = 3,
            cooldownMs = 800L,
            onShakeTriggered = { triggerCount++ }
        )

        var time = 1000L
        detector.addSample(100f, 100f, time)

        // 1st shake cycle -> triggers at time = 1200L
        time += 50L; detector.addSample(150f, 100f, time)
        time += 50L; detector.addSample(90f, 100f, time)
        time += 50L; detector.addSample(160f, 100f, time)
        time += 50L; detector.addSample(90f, 100f, time)
        assertEquals(1, triggerCount)

        // Try to shake again at time = 1400L (inside 800ms cooldown: 1200 + 800 = 2000L)
        time += 50L; detector.addSample(150f, 100f, time)
        time += 50L; detector.addSample(90f, 100f, time)
        time += 50L; detector.addSample(160f, 100f, time)
        time += 50L; detector.addSample(90f, 100f, time)
        // Should still be 1!
        assertEquals(1, triggerCount)

        // After cooldown expires (time = 2100L)
        time = 2100L
        detector.reset()
        detector.addSample(100f, 100f, time)
        time += 50L; detector.addSample(150f, 100f, time)
        time += 50L; detector.addSample(90f, 100f, time)
        time += 50L; detector.addSample(160f, 100f, time)
        time += 50L; detector.addSample(90f, 100f, time)
        assertEquals(2, triggerCount)
    }

    @Test
    fun testResetClearsIntermediateStrokes() {
        var triggerCount = 0
        var strokeCount = 0

        val detector = BubbleShakeDetector(
            density = density,
            targetStrokes = 3,
            onShakeStroke = { count, _, _, _ -> strokeCount = count },
            onShakeTriggered = { triggerCount++ }
        )

        var time = 1000L
        detector.addSample(100f, 100f, time)
        time += 50L; detector.addSample(150f, 100f, time)
        time += 50L; detector.addSample(90f, 100f, time) // Stroke 1
        time += 50L; detector.addSample(160f, 100f, time) // Stroke 2
        assertEquals(2, strokeCount)

        // Reset
        detector.reset()
        assertFalse(detector.isShaking)

        // Next stroke after reset should start as stroke 1, not stroke 3
        time += 50L; detector.addSample(100f, 100f, time)
        time += 50L; detector.addSample(150f, 100f, time)
        time += 50L; detector.addSample(90f, 100f, time)
        assertEquals(1, strokeCount)
        assertEquals(0, triggerCount)
    }
}
