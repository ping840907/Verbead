package com.ping.verbead.bubble

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import com.ping.verbead.R

/**
 * Controller managing the outer pulse ring volume indicator window
 * that expands and contracts in real-time around the floating bubble based on audio RMS.
 */
class BubblePulseController(
    private val context: Context,
    private val windowManager: WindowManager,
    private val themedCtx: ContextThemeWrapper
) {
    companion object {
        private const val TAG = "BubblePulseCtrl"
    }

    private var pulseView: View? = null
    private var pulseLayoutParams: WindowManager.LayoutParams? = null
    private var viewPulseRing: View? = null
    private var viewPulseWave: View? = null
    var isShowing: Boolean = false
        private set
    private var lastWaveTime = 0L

    fun show(anchorX: Int, anchorY: Int) {
        if (isShowing) {
            updatePosition(anchorX, anchorY)
            return
        }
        isShowing = true

        if (pulseView == null) {
            pulseView = LayoutInflater.from(themedCtx).inflate(R.layout.layout_bubble_pulse, null).apply {
                viewPulseRing = findViewById(R.id.view_bubble_pulse_ring)
                viewPulseWave = findViewById(R.id.view_bubble_pulse_wave)
            }
        }
        val view = pulseView ?: return
        val density = context.resources.displayMetrics.density

        val layoutFlag = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

        val pulseSize = (104 * density).toInt()
        val bubbleWidthPx = (60 * density).toInt()
        val bubbleHeightPx = (60 * density).toInt()
        val xOffset = anchorX + ((bubbleWidthPx - pulseSize) / 2)
        val yOffset = anchorY + ((bubbleHeightPx - pulseSize) / 2)

        pulseLayoutParams = WindowManager.LayoutParams(
            pulseSize,
            pulseSize,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = xOffset
            y = yOffset
        }

        view.animate().cancel()
        viewPulseRing?.animate()?.cancel()
        viewPulseWave?.animate()?.cancel()

        if (view.isAttachedToWindow) {
            try {
                windowManager.removeView(view)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to remove previous pulseView", e)
            }
        }

        viewPulseRing?.alpha = 0.25f
        viewPulseRing?.scaleX = 1.0f
        viewPulseRing?.scaleY = 1.0f

        viewPulseWave?.alpha = 0f
        viewPulseWave?.scaleX = 1.0f
        viewPulseWave?.scaleY = 1.0f

        try {
            windowManager.addView(view, pulseLayoutParams)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add pulseView to WindowManager", e)
        }
    }

    fun updatePosition(anchorX: Int, anchorY: Int) {
        val view = pulseView ?: return
        if (!isShowing || !view.isAttachedToWindow) return
        val lp = pulseLayoutParams ?: return
        val density = context.resources.displayMetrics.density
        val pulseSize = (104 * density).toInt()
        val bubbleWidthPx = (60 * density).toInt()
        val bubbleHeightPx = (60 * density).toInt()
        lp.x = anchorX + ((bubbleWidthPx - pulseSize) / 2)
        lp.y = anchorY + ((bubbleHeightPx - pulseSize) / 2)
        try {
            windowManager.updateViewLayout(view, lp)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update pulse layout", e)
        }
    }

    fun hide() {
        if (!isShowing) return
        isShowing = false
        val view = pulseView ?: return
        viewPulseRing?.animate()?.cancel()
        viewPulseWave?.animate()?.cancel()

        viewPulseRing?.animate()
            ?.alpha(0f)
            ?.scaleX(1.0f)
            ?.scaleY(1.0f)
            ?.setDuration(160)
            ?.withEndAction {
                if (view.isAttachedToWindow && !isShowing) {
                    try {
                        windowManager.removeView(view)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to remove pulseView on hide", e)
                    }
                }
            }
            ?.start()

        viewPulseWave?.animate()?.alpha(0f)?.setDuration(120)?.start()
    }

    fun onAudioRmsUpdate(rms: Float, isRecording: Boolean) {
        if (!isRecording || !isShowing) return
        Handler(Looper.getMainLooper()).post {
            if (!isRecording || !isShowing) return@post
            val ring = viewPulseRing ?: return@post
            val wave = viewPulseWave ?: return@post

            // RMS 能量範圍通常介於 0.005（靜音底噪）~ 0.18（清晰說話）
            val norm = ((rms - 0.008f) / 0.12f).coerceIn(0f, 1f)

            // 動態外擴比例：1.0f（同懸浮球大小）~ 1.48f（外擴脈衝）
            val targetScale = 1.0f + norm * 0.48f
            val targetAlpha = if (norm > 0.05f) (0.25f + norm * 0.55f).coerceAtMost(0.85f) else 0.12f

            ring.animate()
                .scaleX(targetScale)
                .scaleY(targetScale)
                .alpha(targetAlpha)
                .setDuration(70)
                .start()

            // 說話重音峰值時觸發擴散外波（如音量大於門檻且間隔達 320ms）
            val now = android.os.SystemClock.uptimeMillis()
            if (norm > 0.35f && (now - lastWaveTime > 320L)) {
                lastWaveTime = now
                wave.animate().cancel()
                wave.scaleX = 1.05f
                wave.scaleY = 1.05f
                wave.alpha = 0.65f
                wave.animate()
                    .scaleX(1.55f)
                    .scaleY(1.55f)
                    .alpha(0f)
                    .setDuration(380)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
        }
    }

    fun destroy() {
        isShowing = false
        val view = pulseView ?: return
        if (view.isAttachedToWindow) {
            try {
                windowManager.removeView(view)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to remove pulseView on destroy", e)
            }
        }
        pulseView = null
    }
}
