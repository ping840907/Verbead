package com.ping.verbead.bubble

import kotlin.math.abs

/**
 * Detects intentional back-and-forth wiggling/shaking gestures while dragging a floating bubble.
 * Tracks rapid direction reversals within a sliding time window to trigger bubble duplication,
 * while providing progress callbacks for progressive haptic ticks and jelly squish/stretch deformation.
 */
class BubbleShakeDetector(
    private val density: Float,
    private val minStrokeDp: Float = 16f,
    private val maxWindowMs: Long = 650L,
    private val targetStrokes: Int = 3,
    private val cooldownMs: Long = 900L,
    private val timeProvider: () -> Long = { System.currentTimeMillis() },
    private val onShakeStroke: ((strokeCount: Int, targetStrokes: Int, progress: Float, isHorizontal: Boolean) -> Unit)? = null,
    private val onShakeTriggered: (() -> Unit)? = null
) {
    private val minStrokePx = minStrokeDp * density
    private val strokeTimestamps = mutableListOf<Long>()

    private var strokeStartX = 0f
    private var strokeStartY = 0f
    private var currentDirX = 0 // -1: Left, +1: Right, 0: Undecided
    private var currentDirY = 0 // -1: Up, +1: Down, 0: Undecided
    private var hasInitializedPos = false
    private var cooldownUntilMs = 0L

    var isShaking: Boolean = false
        private set

    /**
     * Feeds raw touch coordinates from MotionEvent during dragging.
     */
    fun addSample(rawX: Float, rawY: Float, timestampMs: Long = timeProvider()) {
        if (timestampMs < cooldownUntilMs) return

        if (!hasInitializedPos) {
            strokeStartX = rawX
            strokeStartY = rawY
            hasInitializedPos = true
            return
        }

        // Prune turnaround events that fall outside the sliding window
        strokeTimestamps.removeAll { timestampMs - it > maxWindowMs }

        val dx = rawX - strokeStartX
        val dy = rawY - strokeStartY

        var strokeDetected = false
        var isHorizontal = true

        // 1. Check Horizontal Direction Reversal (Left <-> Right)
        if (abs(dx) >= minStrokePx) {
            val dirX = if (dx > 0) 1 else -1
            if (currentDirX != 0 && dirX != currentDirX) {
                strokeTimestamps.add(timestampMs)
                strokeDetected = true
                isHorizontal = true
            }
            currentDirX = dirX
            strokeStartX = rawX
        }

        // 2. Check Vertical Direction Reversal (Up <-> Down)
        if (abs(dy) >= minStrokePx) {
            val dirY = if (dy > 0) 1 else -1
            if (currentDirY != 0 && dirY != currentDirY) {
                if (!strokeDetected) {
                    strokeTimestamps.add(timestampMs)
                    strokeDetected = true
                    isHorizontal = false
                }
            }
            currentDirY = dirY
            strokeStartY = rawY
        }

        if (strokeDetected) {
            isShaking = true
            val strokeCount = strokeTimestamps.size
            val progress = (strokeCount.toFloat() / targetStrokes).coerceIn(0f, 1f)
            onShakeStroke?.invoke(strokeCount, targetStrokes, progress, isHorizontal)

            if (strokeCount >= targetStrokes) {
                cooldownUntilMs = timestampMs + cooldownMs
                strokeTimestamps.clear()
                currentDirX = 0
                currentDirY = 0
                isShaking = false
                onShakeTriggered?.invoke()
            }
        } else if (strokeTimestamps.isEmpty()) {
            isShaking = false
        }
    }

    /**
     * Resets detector state when dragging begins or ends.
     */
    fun reset() {
        strokeTimestamps.clear()
        currentDirX = 0
        currentDirY = 0
        hasInitializedPos = false
        strokeStartX = 0f
        strokeStartY = 0f
        isShaking = false
    }
}
