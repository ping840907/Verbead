package com.ping.verbead.ui

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import com.ping.verbead.R

/**
 * The coloured shadow under a Sea Glass bubble.
 *
 * The bubble's own window is a fixed 60dp, the ball fills nearly all of it, so there is no room
 * to draw a glow around it. This is a second, larger, non-touchable window placed right behind
 * the bubble's window and kept anchored to it: [move] follows every position change, and
 * the appearance (visibility, alpha, scale, colour) is re-read from the bubble before each frame.
 */
class BubbleGlowWindow(
    private val base: WindowManager,
    private val bubbleRoot: View
) {
    private val density = bubbleRoot.resources.displayMetrics.density
    private val padPx = (PAD_DP * density).toInt()
    private val glowView = GlowView(bubbleRoot.context, density)
    private var params: WindowManager.LayoutParams? = null
    private var added = false

    val view: View get() = glowView

    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        syncAppearance()
        true
    }

    fun add(bubbleParams: WindowManager.LayoutParams) {
        if (added || Build.VERSION.SDK_INT < 28) return
        val lp = WindowManager.LayoutParams(
            bubbleParams.width + 2 * padPx,
            bubbleParams.height + 2 * padPx,
            bubbleParams.type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = bubbleParams.x - padPx
            y = bubbleParams.y - padPx
        }
        params = lp
        runCatching { base.addView(glowView, lp) }.onSuccess {
            added = true
            bubbleRoot.viewTreeObserver.addOnPreDrawListener(preDraw)
        }
    }

    fun move(bubbleParams: WindowManager.LayoutParams) {
        val lp = params ?: return
        if (!added) return
        val x = bubbleParams.x - padPx
        val y = bubbleParams.y - padPx
        if (lp.x == x && lp.y == y) return
        lp.x = x
        lp.y = y
        runCatching { base.updateViewLayout(glowView, lp) }
    }

    fun remove() {
        if (!added) return
        added = false
        runCatching { bubbleRoot.viewTreeObserver.removeOnPreDrawListener(preDraw) }
        runCatching { base.removeViewImmediate(glowView) }
    }

    private fun syncAppearance() {
        val ball = bubbleRoot.findViewById<GlassBubbleLayout>(R.id.btn_bubble_mic)
        val color = ball?.glowColor ?: 0
        glowView.setGlowColor(color)
        glowView.visibility =
            if (color == 0 || bubbleRoot.visibility != View.VISIBLE) View.INVISIBLE else View.VISIBLE
        glowView.alpha = bubbleRoot.alpha * (ball?.alpha ?: 1f)
        glowView.scaleX = bubbleRoot.scaleX * (ball?.scaleX ?: 1f)
        glowView.scaleY = bubbleRoot.scaleY * (ball?.scaleY ?: 1f)
        glowView.translationX = bubbleRoot.translationX + (ball?.translationX ?: 0f)
        glowView.translationY = bubbleRoot.translationY + (ball?.translationY ?: 0f)
    }

    private class GlowView(context: Context, private val density: Float) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            if (Build.VERSION.SDK_INT >= 28) {
                maskFilter = BlurMaskFilter(BLUR_DP * density, BlurMaskFilter.Blur.NORMAL)
            }
        }

        fun setGlowColor(color: Int) {
            if (paint.color == color) return
            paint.color = color
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            if (paint.color == 0) return
            // Centred on the original 56dp ball, nudged down so it reads as a shadow rather than a halo.
            canvas.drawCircle(width / 2f, height / 2f + OFFSET_DP * density, BALL_DP / 2f * density, paint)
        }
    }

    private companion object {
        const val PAD_DP = 14
        const val BALL_DP = 56f
        const val BLUR_DP = 7f
        const val OFFSET_DP = 2.5f
    }
}
