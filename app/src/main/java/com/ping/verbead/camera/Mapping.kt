package com.ping.verbead.camera

import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.roundToInt

data class FrameMetrics(
    var width: Int = 0,
    var height: Int = 0,
    var orientation: Int = 0
) {
    fun isValid() = width > 0 && height > 0
}

fun Rect.setFrameRoi(
    frameMetrics: FrameMetrics,
    viewRect: Rect,
    viewRoi: Rect
) {
    Matrix().apply {
        setTranslate(-viewRect.left.toFloat(), -viewRect.top.toFloat())
        postScale(1f / viewRect.width(), 1f / viewRect.height())
        postRotate(-frameMetrics.orientation.toFloat(), .5f, .5f)
        postScale(frameMetrics.width.toFloat(), frameMetrics.height.toFloat())
        val frameRoiF = RectF()
        val viewRoiF = RectF(
            viewRoi.left.toFloat(),
            viewRoi.top.toFloat(),
            viewRoi.right.toFloat(),
            viewRoi.bottom.toFloat()
        )
        mapRect(frameRoiF, viewRoiF)
        set(
            frameRoiF.left.roundToInt(),
            frameRoiF.top.roundToInt(),
            frameRoiF.right.roundToInt(),
            frameRoiF.bottom.roundToInt()
        )
    }
    val clampedLeft = left.coerceIn(0, frameMetrics.width)
    val clampedTop = top.coerceIn(0, frameMetrics.height)
    val clampedRight = right.coerceIn(0, frameMetrics.width)
    val clampedBottom = bottom.coerceIn(0, frameMetrics.height)
    if (clampedLeft >= clampedRight || clampedTop >= clampedBottom) {
        setEmpty()
    } else {
        set(clampedLeft, clampedTop, clampedRight, clampedBottom)
    }
}

fun Rect.setCovered(
    viewWidth: Int,
    viewHeight: Int,
    frameMetrics: FrameMetrics
) {
    val frameWidth: Int
    val frameHeight: Int
    when (frameMetrics.orientation) {
        90, 270 -> {
            frameWidth = frameMetrics.height
            frameHeight = frameMetrics.width
        }
        else -> {
            frameWidth = frameMetrics.width
            frameHeight = frameMetrics.height
        }
    }
    if (frameWidth < 1 || frameHeight < 1) {
        set(0, 0, 0, 0)
        return
    }
    var coveredWidth = frameWidth
    var coveredHeight = frameHeight
    if (viewWidth.toLong() * coveredWidth <
        viewHeight.toLong() * coveredHeight
    ) {
        coveredWidth = coveredWidth * viewHeight / coveredHeight
        coveredHeight = viewHeight
    } else {
        coveredHeight = coveredHeight * viewWidth / coveredWidth
        coveredWidth = viewWidth
    }
    val left = (viewWidth - coveredWidth) / 2
    val top = (viewHeight - coveredHeight) / 2
    set(
        left,
        top,
        left + coveredWidth,
        top + coveredHeight
    )
}
