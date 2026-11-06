package com.ping.verbead.bubble

import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.widget.FrameLayout

/**
 * Window root of a bubble that is linked behind another one. Overlay windows stack in the order they were added, so a
 * bubble created later always sits on top; to make it look like it slides *under* the bubble in front, this layout
 * does not draw the circle covered by that bubble, letting the front bubble show through.
 */
class UnderlapFrameLayout(context: Context) : FrameLayout(context) {
    private val hole = Path()
    private var active = false

    /** Circle (in this view's own coordinates) that must stay undrawn. */
    fun setHole(centerX: Float, centerY: Float, radius: Float) {
        hole.reset()
        hole.addCircle(centerX, centerY, radius, Path.Direction.CW)
        active = true
        invalidate()
    }

    fun clearHole() {
        if (active) {
            active = false
            invalidate()
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (!active) {
            super.dispatchDraw(canvas)
            return
        }
        val save = canvas.save()
        canvas.clipOutPath(hole)
        super.dispatchDraw(canvas)
        canvas.restoreToCount(save)
    }
}
