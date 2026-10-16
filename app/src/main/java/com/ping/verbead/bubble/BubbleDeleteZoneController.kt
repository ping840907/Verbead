package com.ping.verbead.bubble

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import com.ping.verbead.R
import com.ping.verbead.util.HapticUtil
import kotlin.math.hypot

/**
 * Manages the floating "X" delete zone window at the upper reachable area of the screen.
 * Handles entrance/exit animations, gravitational magnetic attraction, visual state switching,
 * and deletion confirmation animations when a bubble is dropped into the container.
 */
class BubbleDeleteZoneController(
    private val context: Context,
    private val windowManager: WindowManager
) {
    companion object {
        private const val TAG = "BubbleDeleteZone"
        private const val WINDOW_SIZE_DP = 84f
        private const val INNER_SIZE_DP = 60f
        private const val ATTRACTION_RADIUS_DP = 85f
        private const val LOCK_RADIUS_DP = 38f
    }

    data class MagnetismResult(
        val isAttracted: Boolean,
        val isLocked: Boolean,
        val targetX: Float,
        val targetY: Float
    )

    private var rootView: View? = null
    private var containerTarget: FrameLayout? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    var isShowing: Boolean = false
        private set

    var isAttracted: Boolean = false
        private set

    var isLocked: Boolean = false
        private set

    private var cachedCenterX: Float = 0f
    private var cachedCenterY: Float = 0f

    /**
     * Shows the delete target container with a scale and fade entrance animation.
     */
    fun show(screenWidth: Int, targetY: Int) {
        if (isShowing) return

        val density = context.resources.displayMetrics.density
        val windowSizePx = (WINDOW_SIZE_DP * density).toInt()

        if (rootView == null) {
            rootView = LayoutInflater.from(context).inflate(R.layout.layout_bubble_delete_zone, null)
            containerTarget = rootView?.findViewById(R.id.container_delete_target)
        }
        val view = rootView ?: return

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val posX = (screenWidth - windowSizePx) / 2
        val posY = targetY

        cachedCenterX = posX + (windowSizePx / 2f)
        cachedCenterY = posY + (windowSizePx / 2f)

        layoutParams = WindowManager.LayoutParams(
            windowSizePx,
            windowSizePx,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = posX
            y = posY
        }

        isAttracted = false
        isLocked = false

        containerTarget?.apply {
            setBackgroundResource(R.drawable.bg_bubble_delete_zone)
            scaleX = 1f
            scaleY = 1f
        }

        view.alpha = 0f
        view.scaleX = 0.5f
        view.scaleY = 0.5f

        if (view.isAttachedToWindow) {
            try {
                windowManager.removeView(view)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to remove previous delete zone view", e)
            }
        }

        try {
            windowManager.addView(view, layoutParams)
            isShowing = true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to add delete zone window", e)
            return
        }

        view.animate().cancel()
        view.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(220L)
            .setInterpolator(OvershootInterpolator(1.15f))
            .start()
    }

    /**
     * Evaluates magnetic attraction between the dragged bubble and the delete zone center.
     */
    fun updateMagnetism(
        bubbleCenterX: Float,
        bubbleCenterY: Float,
        bubbleRadiusPx: Float,
        rawBubbleX: Float,
        rawBubbleY: Float
    ): MagnetismResult {
        if (!isShowing) {
            return MagnetismResult(isAttracted = false, isLocked = false, targetX = rawBubbleX, targetY = rawBubbleY)
        }

        val density = context.resources.displayMetrics.density
        val attractDist = ATTRACTION_RADIUS_DP * density
        val lockDist = LOCK_RADIUS_DP * density

        val dist = hypot(bubbleCenterX - cachedCenterX, bubbleCenterY - cachedCenterY)

        val targetLockX = cachedCenterX - bubbleRadiusPx
        val targetLockY = cachedCenterY - bubbleRadiusPx

        if (dist < attractDist) {
            if (!isAttracted) {
                isAttracted = true
                HapticUtil.click(context)
                containerTarget?.apply {
                    setBackgroundResource(R.drawable.bg_bubble_delete_zone_active)
                    animate().cancel()
                    animate().scaleX(1.18f).scaleY(1.18f).setDuration(160L).start()
                }
            }

            if (dist < lockDist) {
                isLocked = true
                return MagnetismResult(
                    isAttracted = true,
                    isLocked = true,
                    targetX = targetLockX,
                    targetY = targetLockY
                )
            } else {
                isLocked = false
                val factor = (1f - (dist - lockDist) / (attractDist - lockDist)).coerceIn(0f, 1f)
                val blendedX = rawBubbleX + (targetLockX - rawBubbleX) * (factor * 0.72f)
                val blendedY = rawBubbleY + (targetLockY - rawBubbleY) * (factor * 0.72f)
                return MagnetismResult(
                    isAttracted = true,
                    isLocked = false,
                    targetX = blendedX,
                    targetY = blendedY
                )
            }
        } else {
            if (isAttracted) {
                isAttracted = false
                isLocked = false
                HapticUtil.tick(context)
                containerTarget?.apply {
                    setBackgroundResource(R.drawable.bg_bubble_delete_zone)
                    animate().cancel()
                    animate().scaleX(1f).scaleY(1f).setDuration(160L).start()
                }
            }
            return MagnetismResult(
                isAttracted = false,
                isLocked = false,
                targetX = rawBubbleX,
                targetY = rawBubbleY
            )
        }
    }

    /**
     * Plays a deletion burst / shrink confirmation effect when a bubble is dropped in the zone.
     */
    fun playDeleteBurst(onComplete: () -> Unit) {
        val target = containerTarget ?: run {
            hide(animated = false, onComplete = onComplete)
            return
        }

        HapticUtil.heavyClick(context)
        target.animate().cancel()
        target.animate()
            .scaleX(1.35f)
            .scaleY(1.35f)
            .alpha(0f)
            .setDuration(160L)
            .withEndAction {
                hide(animated = false, onComplete = onComplete)
            }
            .start()
    }

    /**
     * Hides the delete zone container with an exit scale and fade animation.
     */
    fun hide(animated: Boolean = true, onComplete: (() -> Unit)? = null) {
        val view = rootView ?: run {
            isShowing = false
            onComplete?.invoke()
            return
        }
        if (!isShowing) {
            onComplete?.invoke()
            return
        }
        isShowing = false
        isAttracted = false
        isLocked = false

        if (!animated || !view.isAttachedToWindow) {
            try {
                if (view.isAttachedToWindow) {
                    windowManager.removeView(view)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to remove delete zone view", e)
            }
            onComplete?.invoke()
            return
        }

        view.animate().cancel()
        view.animate()
            .alpha(0f)
            .scaleX(0.5f)
            .scaleY(0.5f)
            .setDuration(180L)
            .withEndAction {
                try {
                    if (view.isAttachedToWindow) {
                        windowManager.removeView(view)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to remove delete zone view on hide end", e)
                }
                onComplete?.invoke()
            }
            .start()
    }
}
