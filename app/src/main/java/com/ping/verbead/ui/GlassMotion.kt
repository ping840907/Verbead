package com.ping.verbead.ui

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator

/**
 * Small, quick motions shared by the screens and the floating UI. Everything goes through
 * ViewPropertyAnimator, so the system's "animator duration scale" (and 0 = off) is respected.
 */
object GlassMotion {

    /** Presses in on touch-down and springs back on release. The listener never consumes the event. */
    @SuppressLint("ClickableViewAccessibility")
    fun press(touchSource: View, scaled: View = touchSource, scale: Float = 0.96f) {
        touchSource.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> scaled.animate().cancel().also {
                    scaled.animate().scaleX(scale).scaleY(scale).setDuration(90).setInterpolator(DecelerateInterpolator()).start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> springBack(scaled)
            }
            false
        }
    }

    /** Back to full size with a small overshoot, like a pressed key letting go. */
    fun springBack(v: View) {
        v.animate().cancel()
        v.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(OvershootInterpolator(3f)).start()
    }

    /** Cards rise and fade in one after another when a screen opens. */
    fun staggerIn(views: List<View>, rise: Float, baseDelay: Long = 30L, step: Long = 45L) {
        views.forEachIndexed { i, v ->
            val finalAlpha = v.alpha
            v.alpha = 0f
            v.translationY = rise
            v.animate()
                .alpha(finalAlpha)
                .translationY(0f)
                .setStartDelay(baseDelay + i * step)
                .setDuration(340)
                .setInterpolator(DecelerateInterpolator(1.8f))
                .start()
        }
    }

    internal fun clickableRows(root: View, out: MutableList<View>, inCard: Boolean = false) {
        val isCard = root is GlassCard
        if (inCard && root.isClickable && root !is ViewGroupSkipMarker) out.add(root)
        if (root is ViewGroup) for (i in 0 until root.childCount) clickableRows(root.getChildAt(i), out, inCard || isCard)
    }
}

/** Marker for views that already handle their own touch feedback. */
internal interface ViewGroupSkipMarker
