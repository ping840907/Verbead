package com.ping.verbead.bubble

import android.content.Context
import android.graphics.PixelFormat
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.google.android.material.card.MaterialCardView
import com.ping.verbead.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Controller managing the floating speech preview card and tooltip window.
 */
class BubblePreviewController(
    private val context: Context,
    private val windowManager: WindowManager,
    private val themedCtx: ContextThemeWrapper,
    private val scope: CoroutineScope
) {
    private lateinit var previewLayoutParams: WindowManager.LayoutParams
    lateinit var previewView: View
        private set
    private lateinit var cardBubblePreview: MaterialCardView
    private lateinit var tvBubblePreview: TextView
    private var autoHidePreviewJob: Job? = null

    val isInitialized: Boolean
        get() = ::previewView.isInitialized

    val isAttachedToWindow: Boolean
        get() = ::previewView.isInitialized && previewView.isAttachedToWindow

    fun setup(bubbleWidthPx: Int, initialY: Int, density: Float) {
        previewView = LayoutInflater.from(themedCtx).inflate(R.layout.layout_floating_preview, null)
        cardBubblePreview = previewView.findViewById(R.id.card_bubble_preview)
        tvBubblePreview = previewView.findViewById(R.id.tv_bubble_preview)

        val layoutFlag = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        previewLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.RIGHT
            x = bubbleWidthPx + (8 * density).toInt()
            y = initialY + (12 * density).toInt()
        }

        previewView.visibility = View.GONE
        windowManager.addView(previewView, previewLayoutParams)
    }

    fun updatePosition(
        anchorY: Int,
        isDockedOnRight: Boolean,
        screenWidth: Int,
        safeBottom: Int
    ) {
        if (!::previewView.isInitialized) return
        val density = context.resources.displayMetrics.density
        val padding = (14 * density).toInt()
        val visualGap = (8 * density).toInt()
        val bubbleWidthPx = (60 * density).toInt()
        val windowX = bubbleWidthPx + visualGap - padding

        if (isDockedOnRight) {
            previewLayoutParams.gravity = Gravity.TOP or Gravity.RIGHT
            previewLayoutParams.x = windowX
        } else {
            previewLayoutParams.gravity = Gravity.TOP or Gravity.LEFT
            previewLayoutParams.x = windowX
        }

        val maxAvailableWidth = screenWidth - bubbleWidthPx - visualGap - padding
        tvBubblePreview.maxWidth = maxOf((120 * density).toInt(), maxAvailableWidth)

        val estHeight = previewView.measuredHeight.takeIf { it > 0 } ?: (48 * density).toInt()
        val targetY = anchorY + (12 * density).toInt() - padding
        val maxAllowedY = safeBottom - estHeight - (8 * density).toInt()
        previewLayoutParams.y = targetY.coerceIn(0, maxOf(0, maxAllowedY))

        if (previewView.isAttachedToWindow) {
            windowManager.updateViewLayout(previewView, previewLayoutParams)
        }
    }

    fun show(
        msg: String,
        autoHide: Boolean,
        anchorY: Int,
        isDockedOnRight: Boolean,
        screenWidth: Int,
        safeBottom: Int,
        onUntuck: () -> Unit
    ) {
        if (!::previewView.isInitialized) return
        onUntuck()
        tvBubblePreview.text = msg
        updatePosition(anchorY, isDockedOnRight, screenWidth, safeBottom)
        if (previewView.isAttachedToWindow) {
            previewView.visibility = View.VISIBLE
        }
        autoHidePreviewJob?.cancel()
        if (autoHide) {
            autoHidePreviewJob = scope.launch {
                delay(5000L)
                hide()
            }
        }
    }

    fun hide() {
        autoHidePreviewJob?.cancel()
        if (::previewView.isInitialized && previewView.isAttachedToWindow) {
            previewView.visibility = View.GONE
        }
    }

    fun destroy() {
        autoHidePreviewJob?.cancel()
        if (::previewView.isInitialized && previewView.isAttachedToWindow) {
            try {
                windowManager.removeView(previewView)
            } catch (_: Exception) {}
        }
    }
}
