package com.ping.verbead.bubble

import kotlin.math.hypot

/**
 * Position and boundary state of a floating bubble for physics calculations.
 */
data class BubblePosition(
    val id: Int,
    var x: Float,
    var y: Float,
    val radius: Float
) {
    val cx: Float get() = x + radius
    val cy: Float get() = y + radius

    fun setCenter(newCx: Float, newCy: Float) {
        x = newCx - radius
        y = newCy - radius
    }
}

/**
 * Physics collision resolution and edge stacking engine for multi-bubble layouts.
 * Ensures floating bubbles push each other dynamically and never overlap.
 */
object BubblePhysics {

    /**
     * Resolves circular collisions among multiple bubbles when one bubble is being dragged.
     *
     * @param bubbles List of bubble positions
     * @param draggedId The ID of the bubble currently held by user's finger (infinite mass)
     * @param minDragX Minimum allowable left X coordinate
     * @param maxDragX Maximum allowable left X coordinate
     * @param minDragY Minimum allowable top Y coordinate
     * @param maxDragY Maximum allowable top Y coordinate
     * @param gap Additional padding between bubbles (default 8px)
     * @return true if any non-dragged bubble was pushed/moved by collision
     */
    fun resolveCollisions(
        bubbles: List<BubblePosition>,
        draggedId: Int,
        minDragX: Float,
        maxDragX: Float,
        minDragY: Float,
        maxDragY: Float,
        gap: Float = 8f,
        bubbleBounds: Map<Int, Pair<Float, Float>>? = null
    ): Boolean {
        if (bubbles.size <= 1) return false
        var anyMoved = false

        // Run 3 iterations of relaxation to handle cascade pushes smoothly
        for (iter in 0 until 3) {
            for (i in bubbles.indices) {
                for (j in (i + 1) until bubbles.size) {
                    val b1 = bubbles[i]
                    val b2 = bubbles[j]

                    val minDistance = b1.radius + b2.radius + gap
                    val dx = b2.cx - b1.cx
                    val dy = b2.cy - b1.cy
                    val dist = hypot(dx, dy)

                    if (dist < minDistance) {
                        val isB1Dragged = (b1.id == draggedId)
                        val isB2Dragged = (b2.id == draggedId)

                        val (b1MinX, b1MaxX) = bubbleBounds?.get(b1.id) ?: (minDragX to maxDragX)
                        val (b2MinX, b2MaxX) = bubbleBounds?.get(b2.id) ?: (minDragX to maxDragX)

                        when {
                            isB1Dragged && !isB2Dragged -> {
                                val oldX = b2.x; val oldY = b2.y
                                resolvePair(b2, b1, b2MinX, b2MaxX, minDragY, maxDragY, minDistance)
                                if (b2.x != oldX || b2.y != oldY) anyMoved = true
                            }
                            !isB1Dragged && isB2Dragged -> {
                                val oldX = b1.x; val oldY = b1.y
                                resolvePair(b1, b2, b1MinX, b1MaxX, minDragY, maxDragY, minDistance)
                                if (b1.x != oldX || b1.y != oldY) anyMoved = true
                            }
                            else -> {
                                val oldX1 = b1.x; val oldY1 = b1.y
                                val oldX2 = b2.x; val oldY2 = b2.y
                                val overlap = minDistance - dist
                                val nx = if (dist > 0.001f) dx / dist else 0f
                                val ny = if (dist > 0.001f) dy / dist else 1f
                                b1.setCenter(b1.cx - overlap * 0.5f * nx, b1.cy - overlap * 0.5f * ny)
                                b2.setCenter(b2.cx + overlap * 0.5f * nx, b2.cy + overlap * 0.5f * ny)
                                clamp(b1, b1MinX, b1MaxX, minDragY, maxDragY)
                                clamp(b2, b2MinX, b2MaxX, minDragY, maxDragY)
                                if (b1.x != oldX1 || b1.y != oldY1 || b2.x != oldX2 || b2.y != oldY2) anyMoved = true
                            }
                        }
                    }
                }
            }
        }
        return anyMoved
    }

    private fun resolvePair(
        mover: BubblePosition,
        fixed: BubblePosition,
        minDragX: Float,
        maxDragX: Float,
        minDragY: Float,
        maxDragY: Float,
        minDistance: Float
    ) {
        val dx = mover.cx - fixed.cx
        val dy = mover.cy - fixed.cy
        val dist = hypot(dx, dy)
        if (dist >= minDistance) return

        val overlap = minDistance - dist
        val nx = if (dist > 0.001f) dx / dist else 0f
        val ny = if (dist > 0.001f) dy / dist else 1f
        mover.setCenter(mover.cx + overlap * nx, mover.cy + overlap * ny)
        clamp(mover, minDragX, maxDragX, minDragY, maxDragY)

        // If clamping prevented moving enough in X or Y, deflect along perpendicular axis
        val newDx = mover.cx - fixed.cx
        val newDy = mover.cy - fixed.cy
        val newDist = hypot(newDx, newDy)
        if (newDist < minDistance - 0.01f) {
            val isAtRightWall = mover.x >= maxDragX - 0.5f && nx >= -0.01f
            val isAtLeftWall = mover.x <= minDragX + 0.5f && nx <= 0.01f
            val clampedInX = isAtRightWall || isAtLeftWall
            if (clampedInX) {
                val neededDySq = (minDistance * minDistance) - (newDx * newDx)
                if (neededDySq > 0f) {
                    val neededDy = kotlin.math.sqrt(neededDySq)
                    var dirY = if (newDy >= 0f) 1f else -1f
                    val candidateY = fixed.cy + dirY * neededDy - mover.radius
                    if (candidateY < minDragY && dirY < 0f) {
                        dirY = 1f
                    } else if (candidateY > maxDragY && dirY > 0f) {
                        dirY = -1f
                    }
                    mover.y = (fixed.cy + dirY * neededDy - mover.radius).coerceIn(minDragY, maxDragY)
                }
            } else {
                val isAtBottom = mover.y >= maxDragY - 0.5f && ny >= -0.01f
                val isAtTop = mover.y <= minDragY + 0.5f && ny <= 0.01f
                val clampedInY = isAtBottom || isAtTop
                if (clampedInY) {
                    val neededDxSq = (minDistance * minDistance) - (newDy * newDy)
                    if (neededDxSq > 0f) {
                        val neededDx = kotlin.math.sqrt(neededDxSq)
                        var dirX = if (newDx >= 0f) 1f else -1f
                        val candidateX = fixed.cx + dirX * neededDx - mover.radius
                        if (candidateX < minDragX && dirX < 0f) {
                            dirX = 1f
                        } else if (candidateX > maxDragX && dirX > 0f) {
                            dirX = -1f
                        }
                        mover.x = (fixed.cx + dirX * neededDx - mover.radius).coerceIn(minDragX, maxDragX)
                    }
                }
            }
        }
    }

    private fun clamp(b: BubblePosition, minX: Float, maxX: Float, minY: Float, maxY: Float) {
        b.x = b.x.coerceIn(minX, maxX)
        b.y = b.y.coerceIn(minY, maxY)
    }

    /**
     * Resolves vertical edge stacking for bubbles docked on the same side,
     * ensuring that no two bubbles overlap vertically when settled.
     */
    fun resolveEdgeStacking(
        dockedBubbles: List<BubblePosition>,
        minY: Float,
        maxY: Float,
        gap: Float = 8f
    ): Map<Int, Float> {
        if (dockedBubbles.isEmpty()) return emptyMap()
        if (dockedBubbles.size == 1) return mapOf(dockedBubbles[0].id to dockedBubbles[0].y.coerceIn(minY, maxY))

        // Sort bubbles top-to-bottom by current Y
        val sorted = dockedBubbles.sortedBy { it.y }.toMutableList()
        val targetYs = sorted.map { it.y }.toMutableList()

        // Forward pass: push downwards if overlapping
        for (i in 0 until sorted.size - 1) {
            val requiredDistance = sorted[i].radius + sorted[i + 1].radius + gap
            if (targetYs[i + 1] < targetYs[i] + requiredDistance) {
                targetYs[i + 1] = targetYs[i] + requiredDistance
            }
        }

        // Backward pass: if lowest bubble exceeds maxY, push upwards
        val lastIdx = targetYs.size - 1
        if (targetYs[lastIdx] > maxY) {
            targetYs[lastIdx] = maxY
            for (i in lastIdx - 1 downTo 0) {
                val requiredDistance = sorted[i].radius + sorted[i + 1].radius + gap
                if (targetYs[i] > targetYs[i + 1] - requiredDistance) {
                    targetYs[i] = targetYs[i + 1] - requiredDistance
                }
            }
        }

        // Final clamp check
        for (i in targetYs.indices) {
            targetYs[i] = targetYs[i].coerceIn(minY, maxY)
        }

        return sorted.indices.associate { sorted[it].id to targetYs[it] }
    }
}
