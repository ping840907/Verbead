package com.ping.verbead.bubble

import android.content.Context
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
    private var xButtonLayoutParams: WindowManager.LayoutParams? = null
    var isShowing: Boolean = false
        private set

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
            }
        }
        val view = xButtonView ?: return
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
            gravity = Gravity.TOP or Gravity.LEFT
            x = clampedX
            y = targetY
        }

        view.animate().cancel()
        if (view.isAttachedToWindow) {
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
                if (view.isAttachedToWindow) {
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
        if (view.isAttachedToWindow) {
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
