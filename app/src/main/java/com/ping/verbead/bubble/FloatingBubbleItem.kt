package com.ping.verbead.bubble

import android.animation.ValueAnimator
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar

/**
 * Encapsulates view, layout parameters, and motion state for an individual floating bubble.
 */
class FloatingBubbleItem(
    val id: Int,
    var mode: Int,
    val view: View,
    val windowLayoutParams: WindowManager.LayoutParams,
    val btnBubble: FrameLayout,
    val ivIcon: ImageView,
    val progress: ProgressBar
) {
    var isDockedOnRight: Boolean = true
    var isTucked: Boolean = false
    var isBubbleMoving: Boolean = false
    var snapAnimator: ValueAnimator? = null
}
