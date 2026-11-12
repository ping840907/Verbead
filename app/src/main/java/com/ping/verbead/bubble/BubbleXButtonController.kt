package com.ping.verbead.bubble

import com.ping.verbead.util.isViewAdded
import android.content.Context
import com.ping.verbead.util.allowDisplayCutout
import android.graphics.PixelFormat
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import com.ping.verbead.R

/**
 * Controller managing the standalone floating X (cancel / undo) button overlay window.
 */
class BubbleXButtonController(
    private val context: Context,
    private val windowManager: WindowManager,
    private val themedCtx: ContextThemeWrapper,
    private val onXButtonClick: () -> Unit
) {
    companion object {
        private const val TAG = "BubbleXButtonCtrl"
    }

    private var xButtonView: View? = null
    private var undoMode = false
    private var shownUndoMode = false
    private var xButtonLayoutParams: WindowManager.LayoutParams? = null
    var isShowing: Boolean = false
        private set

    /**
     * The button is a cancel (X) while something is running or open, and an undo (back arrow) once the text
     * has been filled in. Only the icon changes; the click handler decides what to do.
     */
    fun setUndo(undo: Boolean) {
        undoMode = undo
        val view = xButtonView ?: return
        if (!isShowing) {
            applyIcon(view, animate = false)
            return
        }
        applyIcon(view, animate = true)
    }

    private fun applyIcon(view: View, animate: Boolean) {
        if (shownUndoMode == undoMode) return
        shownUndoMode = undoMode
        val icon = view.findViewById<android.widget.ImageView>(R.id.iv_bubble_x_icon) ?: return
        val res = if (undoMode) R.drawable.ic_undo_back else R.drawable.ic_close
        icon.contentDescription = context.getString(if (undoMode) R.string.common_undo else R.string.common_close)
        if (!animate) {
            icon.setImageResource(res)
            return
        }
        icon.animate().cancel()
        icon.animate().alpha(0f).rotation(if (undoMode) -40f else 40f).setDuration(80).withEndAction {
            icon.setImageResource(res)
            icon.rotation = if (undoMode) 40f else -40f
            icon.animate().alpha(1f).rotation(0f).setDuration(110).start()
        }.start()
    }

    fun updatePosition(
        anchorX: Int,
        anchorY: Int,
        screenWidth: Int,
        safeBottom: Int,
        statusBarHeight: Int
    ) {
        val view = xButtonView ?: return
        if (!isShowing || !view.isAttachedToWindow) return
        val lp = xButtonLayoutParams ?: return
        val density = context.resources.displayMetrics.density
        val btnSize = (44 * density).toInt()
        val bubbleWidthPx = (60 * density).toInt()

        val preferredY = anchorY - (48 * density).toInt()
        val candidateY = if (preferredY < statusBarHeight) {
            anchorY + bubbleWidthPx + (4 * density).toInt()
        } else {
            preferredY
        }
        val maxAllowedY = safeBottom - btnSize - (4 * density).toInt()
        val targetY = candidateY.coerceIn(0, maxOf(0, maxAllowedY))

        val xOffset = anchorX + ((bubbleWidthPx - btnSize) / 2)
        val clampedX = xOffset.coerceIn(
            (4 * density).toInt(),
            maxOf((4 * density).toInt(), screenWidth - btnSize - (4 * density).toInt())
        )

        lp.x = clampedX
        lp.y = targetY
        try {
            windowManager.updateViewLayout(view, lp)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update xButton layout", e)
        }
    }

    fun show(
        anchorX: Int,
        anchorY: Int,
        screenWidth: Int,
        safeBottom: Int,
        statusBarHeight: Int,
        onUntuck: () -> Unit
    ) {
        onUntuck()
        if (isShowing) {
            updatePosition(anchorX, anchorY, screenWidth, safeBottom, statusBarHeight)
            return
        }
        isShowing = true

        if (xButtonView == null) {
            xButtonView = LayoutInflater.from(themedCtx).inflate(R.layout.layout_bubble_x, null).apply {
                setOnClickListener { onXButtonClick() }
                com.ping.verbead.ui.GlassStage.skin(this)
            }
            shownUndoMode = false
        }
        val view = xButtonView ?: return
        applyIcon(view, animate = false)
        val density = context.resources.displayMetrics.density
        val layoutFlag = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

        val btnSize = (44 * density).toInt()
        val bubbleWidthPx = (60 * density).toInt()
        val xOffset = anchorX + ((bubbleWidthPx - btnSize) / 2)
        val clampedX = xOffset.coerceIn(
            (4 * density).toInt(),
            maxOf((4 * density).toInt(), screenWidth - btnSize - (4 * density).toInt())
        )

        val preferredY = anchorY - (48 * density).toInt()
        val isFlipped = preferredY < statusBarHeight
        val candidateY = if (isFlipped) {
            anchorY + bubbleWidthPx + (4 * density).toInt()
        } else {
            preferredY
        }
        val maxAllowedY = safeBottom - btnSize - (4 * density).toInt()
        val targetY = candidateY.coerceIn(0, maxOf(0, maxAllowedY))

        xButtonLayoutParams = WindowManager.LayoutParams(
            btnSize,
            btnSize,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            allowDisplayCutout()
            gravity = Gravity.TOP or Gravity.LEFT
            x = clampedX
            y = targetY
        }

        view.animate().cancel()
        if (windowManager.isViewAdded(view)) {
            try {
                windowManager.removeView(view)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to remove previous xButtonView", e)
            }
        }
        val dp12 = 12f * density
        view.alpha = 0f
        view.translationY = if (isFlipped) -dp12 else dp12
        view.scaleX = 0.6f
        view.scaleY = 0.6f
        windowManager.addView(view, xButtonLayoutParams)

        view.animate()
            .alpha(1f)
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(200)
            .setInterpolator(OvershootInterpolator(1.2f))
            .start()
    }

    fun hide() {
        if (!isShowing) return
        isShowing = false
        val view = xButtonView ?: return
        view.animate().cancel()
        val density = context.resources.displayMetrics.density

        view.animate()
            .alpha(0f)
            .translationY(10f * density)
            .scaleX(0.6f)
            .scaleY(0.6f)
            .setDuration(160)
            .withEndAction {
                if (windowManager.isViewAdded(view)) {
                    try {
                        windowManager.removeView(view)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to remove xButtonView on hide end", e)
                    }
                }
            }
            .start()
    }

    fun hideImmediately() {
        if (!isShowing) return
        isShowing = false
        val view = xButtonView ?: return
        view.animate().cancel()
        if (windowManager.isViewAdded(view)) {
            try {
                windowManager.removeView(view)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to remove xButtonView immediately", e)
            }
        }
    }

    fun destroy() {
        hideImmediately()
        xButtonView = null
    }
}
