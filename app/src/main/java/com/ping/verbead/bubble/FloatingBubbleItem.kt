package com.ping.verbead.bubble

import android.animation.ValueAnimator
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import com.ping.verbead.R

/**
 * Encapsulates view, layout parameters, and motion state for an individual floating bubble.
 */
class FloatingBubbleItem(
    var id: Int,
    var mode: Int,
    val view: View,
    val windowLayoutParams: WindowManager.LayoutParams,
    val btnBubble: FrameLayout,
    val ivIcon: ImageView,
    val progress: ProgressBar
) {
    /** 收納時露出那一側的小拉出箭頭 */
    private val ivTuckArrow: ImageView? = view.findViewById(R.id.iv_tuck_arrow)

    var isDockedOnRight: Boolean = true
        set(value) {
            field = value
            updateTuckArrow()
        }

    var isTucked: Boolean = false
        set(value) {
            val changed = field != value
            field = value
            updateTuckArrow(animate = changed)
        }

    var isBubbleMoving: Boolean = false
    var snapAnimator: ValueAnimator? = null

    /**
     * 收納時，箭頭放在露出的那一側（貼右時在球的左邊、貼左時在右邊），指向把球拉出來的方向。
     * 展開時淡出隱藏。
     */
    private fun updateTuckArrow(animate: Boolean = false) {
        val arrow = ivTuckArrow ?: return
        val params = arrow.layoutParams as? FrameLayout.LayoutParams
        if (params != null) {
            val side = if (isDockedOnRight) Gravity.START else Gravity.END
            val gravity = Gravity.CENTER_VERTICAL or side
            if (params.gravity != gravity) {
                params.gravity = gravity
                arrow.layoutParams = params
            }
        }
        arrow.scaleX = if (isDockedOnRight) 1f else -1f

        arrow.animate().cancel()
        if (isTucked) {
            if (arrow.visibility != View.VISIBLE) {
                arrow.alpha = 0f
                arrow.visibility = View.VISIBLE
            }
            if (animate) arrow.animate().alpha(0.9f).setDuration(180L).start() else arrow.alpha = 0.9f
        } else if (arrow.visibility == View.VISIBLE) {
            if (animate) {
                arrow.animate().alpha(0f).setDuration(120L).withEndAction { arrow.visibility = View.GONE }.start()
            } else {
                arrow.visibility = View.GONE
            }
        }
    }
}
