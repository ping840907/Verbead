package com.ping.verbead.bubble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class BubblePhysicsTest {

    private val radius = 30f // 60dp diameter
    private val gap = 8f
    private val minDistance = radius * 2 + gap // 68f

    @Test
    fun testNoCollisionWhenFarApart() {
        val b0 = BubblePosition(0, 100f, 100f, radius)
        val b1 = BubblePosition(1, 300f, 300f, radius)
        val bubbles = listOf(b0, b1)

        val moved = BubblePhysics.resolveCollisions(bubbles, draggedId = 0, 0f, 1000f, 0f, 2000f, gap)
        assertFalse(moved)
        assertEquals(100f, b0.x, 0.001f)
        assertEquals(100f, b0.y, 0.001f)
        assertEquals(300f, b1.x, 0.001f)
        assertEquals(300f, b1.y, 0.001f)
    }

    @Test
    fun testDirectCollisionPushesOtherBubble() {
        // b0 (dragged) overlaps b1 horizontally
        val b0 = BubblePosition(0, 100f, 100f, radius)
        val b1 = BubblePosition(1, 120f, 100f, radius) // distance = 20 < 68
        val bubbles = listOf(b0, b1)

        val moved = BubblePhysics.resolveCollisions(bubbles, draggedId = 0, 0f, 1000f, 0f, 2000f, gap)
        assertTrue(moved)
        // b0 must NOT move because it is the dragged bubble
        assertEquals(100f, b0.x, 0.001f)
        assertEquals(100f, b0.y, 0.001f)

        // b1 must be pushed to the right
        val dist = hypot(b1.cx - b0.cx, b1.cy - b0.cy)
        assertTrue("Distance $dist should be >= $minDistance", dist >= minDistance - 0.01f)
        assertTrue("b1 should be to the right of b0", b1.x > b0.x)
    }

    @Test
    fun testCascadePushThreeBubbles() {
        // b0 (dragged) pushed into b1, which is pushed into b2
        val b0 = BubblePosition(0, 100f, 100f, radius)
        val b1 = BubblePosition(1, 130f, 100f, radius)
        val b2 = BubblePosition(2, 160f, 100f, radius)
        val bubbles = listOf(b0, b1, b2)

        val moved = BubblePhysics.resolveCollisions(bubbles, draggedId = 0, 0f, 1000f, 0f, 2000f, gap)
        assertTrue(moved)

        // b0 stays at (100, 100)
        assertEquals(100f, b0.x, 0.001f)

        // Distance between each consecutive pair should be >= minDistance
        val dist01 = hypot(b1.cx - b0.cx, b1.cy - b0.cy)
        val dist12 = hypot(b2.cx - b1.cx, b2.cy - b1.cy)
        assertTrue("dist01 $dist01 >= $minDistance", dist01 >= minDistance - 0.01f)
        assertTrue("dist12 $dist12 >= $minDistance", dist12 >= minDistance - 0.01f)
    }

    @Test
    fun testClampToScreenBoundaries() {
        // b0 pushes b1 towards maxX (300f)
        val b0 = BubblePosition(0, 260f, 100f, radius)
        val b1 = BubblePosition(1, 280f, 100f, radius)
        val bubbles = listOf(b0, b1)

        val maxX = 300f
        BubblePhysics.resolveCollisions(bubbles, draggedId = 0, 0f, maxX, 0f, 1000f, gap)

        assertTrue("b1.x (${b1.x}) should not exceed maxX ($maxX)", b1.x <= maxX)
    }

    @Test
    fun testWallSlidingDeflection() {
        // b1 is pushed against maxX wall and cannot move horizontally, so it deflects vertically
        val maxX = 200f
        val b0 = BubblePosition(0, 180f, 100f, radius)
        val b1 = BubblePosition(1, 190f, 100f, radius)
        val bubbles = listOf(b0, b1)

        BubblePhysics.resolveCollisions(bubbles, draggedId = 0, 0f, maxX, 0f, 1000f, gap)

        assertTrue("b1.x (${b1.x}) must be <= maxX ($maxX)", b1.x <= maxX)
        val dist = hypot(b1.cx - b0.cx, b1.cy - b0.cy)
        assertTrue("Distance $dist should still be >= minDistance $minDistance via vertical sliding", dist >= minDistance - 0.01f)
    }

    @Test
    fun testEdgeStackingSeparatesOverlappingBubbles() {
        // 4 bubbles at x = 0, all overlapping vertically around y = 200..250
        val b0 = BubblePosition(0, 0f, 200f, radius)
        val b1 = BubblePosition(1, 0f, 210f, radius)
        val b2 = BubblePosition(2, 0f, 220f, radius)
        val b3 = BubblePosition(3, 0f, 230f, radius)
        val bubbles = listOf(b0, b1, b2, b3)

        val targets = BubblePhysics.resolveEdgeStacking(bubbles, minY = 100f, maxY = 1500f, gap = gap)
        assertEquals(4, targets.size)

        val sortedYs = targets.values.sorted()
        for (i in 0 until sortedYs.size - 1) {
            val diff = sortedYs[i + 1] - sortedYs[i]
            assertTrue("Vertical gap between $i and ${i + 1} was $diff, expected >= $minDistance", diff >= minDistance - 0.01f)
        }
    }

    @Test
    fun testEdgeStackingPushesUpWhenExceedingMaxY() {
        // Bubbles near the bottom of screen (maxY = 500f)
        val b0 = BubblePosition(0, 0f, 400f, radius)
        val b1 = BubblePosition(1, 0f, 420f, radius)
        val b2 = BubblePosition(2, 0f, 450f, radius)
        val bubbles = listOf(b0, b1, b2)

        val maxY = 500f
        val minY = 50f
        val targets = BubblePhysics.resolveEdgeStacking(bubbles, minY = minY, maxY = maxY, gap = gap)

        // All bubbles should be within [minY, maxY]
        for ((id, y) in targets) {
            assertTrue("Bubble $id y ($y) must be <= maxY ($maxY)", y <= maxY + 0.001f)
            assertTrue("Bubble $id y ($y) must be >= minY ($minY)", y >= minY - 0.001f)
        }

        val sorted = targets.entries.sortedBy { it.value }
        for (i in 0 until sorted.size - 1) {
            val diff = sorted[i + 1].value - sorted[i].value
            assertTrue("Diff between ${sorted[i].key} and ${sorted[i + 1].key} is $diff, expected >= $minDistance", diff >= minDistance - 0.01f)
        }
    }

    @Test
    fun testEdgeStackingWithKeyboardConstrainedMaxY() {
        // Keyboard opens, reducing safe maxY from 1800f down to 800f
        val b0 = BubblePosition(0, 0f, 750f, radius)
        val b1 = BubblePosition(1, 0f, 790f, radius)
        val b2 = BubblePosition(2, 0f, 820f, radius)
        val bubbles = listOf(b0, b1, b2)

        val keyboardMaxY = 800f
        val minY = 100f
        val targets = BubblePhysics.resolveEdgeStacking(bubbles, minY = minY, maxY = keyboardMaxY, gap = gap)

        assertEquals(3, targets.size)
        for ((id, y) in targets) {
            assertTrue("Bubble $id y ($y) must be <= keyboardMaxY ($keyboardMaxY)", y <= keyboardMaxY + 0.001f)
            assertTrue("Bubble $id y ($y) must be >= minY ($minY)", y >= minY - 0.001f)
        }

        val sorted = targets.entries.sortedBy { it.value }
        for (i in 0 until sorted.size - 1) {
            val diff = sorted[i + 1].value - sorted[i].value
            assertTrue("Gap between ${sorted[i].key} and ${sorted[i + 1].key} is $diff, expected >= $minDistance", diff >= minDistance - 0.01f)
        }
    }

    @Test
    fun testCollisionAtRightEdgeWallSlidesVerticallyWithoutExceedingMaxX() {
        val maxX = 300f
        // b0 (dragged) is dragged along the right edge at maxX
        val b0 = BubblePosition(0, maxX, 200f, radius)
        // b1 is resting right at maxX wall
        val b1 = BubblePosition(1, maxX, 220f, radius) // distance 20 < minDistance 68
        val bubbles = listOf(b0, b1)

        val moved = BubblePhysics.resolveCollisions(bubbles, draggedId = 0, 0f, maxX, 100f, 1000f, gap)
        assertTrue("Collision must move b1", moved)
        assertEquals("b0 (dragged) must stay at maxX", maxX, b0.x, 0.001f)
        assertEquals("b0 (dragged) must stay at 200f", 200f, b0.y, 0.001f)
        assertTrue("b1.x (${b1.x}) must NOT exceed maxX ($maxX)", b1.x <= maxX + 0.001f)
        val dist = hypot(b1.cx - b0.cx, b1.cy - b0.cy)
        assertTrue("Distance $dist between b0 and b1 must be >= minDistance $minDistance", dist >= minDistance - 0.01f)
        assertTrue("b1 must have slid down along the edge", b1.y >= 200f + minDistance - 0.01f)
    }

    @Test
    fun testCollisionAtCornerDeflectsTowardsAvailableSpace() {
        val maxX = 300f
        val maxY = 500f
        // b0 pushes b1 which is near the bottom-right corner
        val b0 = BubblePosition(0, maxX, 470f, radius)
        val b1 = BubblePosition(1, maxX, 490f, radius)
        val bubbles = listOf(b0, b1)

        val moved = BubblePhysics.resolveCollisions(bubbles, draggedId = 0, 0f, maxX, 100f, maxY, gap)
        assertTrue("Collision must move b1", moved)
        assertTrue("b1.x must be <= maxX", b1.x <= maxX + 0.001f)
        assertTrue("b1.y must be within [100f, maxY]", b1.y in 100f..maxY)
    }
}
