package com.ping.verbead.bubble

import android.content.Context
import android.graphics.PixelFormat
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
     * 建立刪除區視窗並加進 WindowManager（透明、縮小狀態），回傳視窗；失敗回傳 null。
     * 視窗不接收觸控：判斷是否放進刪除區靠的是球的座標，不需要觸控事件，
     * 這樣預先建好的隱形視窗也不會擋住底下 App 的操作。
     */
    private fun attachWindow(screenWidth: Int, targetY: Int): View? {
        cleanupView()

        val density = context.resources.displayMetrics.density
        val windowSizePx = (WINDOW_SIZE_DP * density).toInt()

        val view = LayoutInflater.from(context).inflate(R.layout.layout_bubble_delete_zone, null)
        rootView = view
        val target = view.findViewById<FrameLayout>(R.id.container_delete_target)
        containerTarget = target

        val layoutFlag = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

        val posX = (screenWidth - windowSizePx) / 2
        val posY = targetY

        cachedCenterX = posX + (windowSizePx / 2f)
        cachedCenterY = posY + (windowSizePx / 2f)

        val lp = WindowManager.LayoutParams(
            windowSizePx,
            windowSizePx,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = posX
            y = posY
        }
        layoutParams = lp

        isAttracted = false
        isLocked = false

        target?.apply {
            alpha = 1f
            scaleX = 1f
            scaleY = 1f
            setBackgroundResource(R.drawable.bg_bubble_delete_zone)
        }

        view.alpha = 0f
        view.scaleX = 0.5f
        view.scaleY = 0.5f

        return try {
            windowManager.addView(view, lp)
            view
        } catch (e: Exception) {
            Log.w(TAG, "Failed to add delete zone window", e)
            cleanupView()
            null
        }
    }

    /**
     * 預先建立刪除區視窗（隱形）。搖晃複製發生在拖曳手勢當中，若當場才載入版面並 addView，
     * 會讓正在被手指拖著走的球卡一兩幀；所以在手指剛按下、球還沒開始動時先備好。
     */
    fun prewarm(screenWidth: Int, targetY: Int) {
        if (isShowing || rootView != null) return
        attachWindow(screenWidth, targetY)
    }

    /** 放開手指後若預先建立的視窗沒用到就移除。 */
    fun discardPrewarm() {
        if (!isShowing) cleanupView()
    }

    /**
     * Shows the delete target container with a scale and fade entrance animation.
     * 已預先建立時只需換位置並播動畫，不再載入版面或 addView。
     */
    fun show(screenWidth: Int, targetY: Int) {
        val current = rootView
        if (isShowing && current != null && current.isAttachedToWindow) {
            return
        }

        val lp = layoutParams
        val view: View
        if (current != null && current.isAttachedToWindow && lp != null) {
            val windowSizePx = (WINDOW_SIZE_DP * context.resources.displayMetrics.density).toInt()
            val posX = (screenWidth - windowSizePx) / 2
            if (lp.x != posX || lp.y != targetY) {
                lp.x = posX
                lp.y = targetY
                try {
                    windowManager.updateViewLayout(current, lp)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to reposition prewarmed delete zone", e)
                }
            }
            cachedCenterX = posX + (windowSizePx / 2f)
            cachedCenterY = targetY + (windowSizePx / 2f)
            isAttracted = false
            isLocked = false
            containerTarget?.apply {
                alpha = 1f
                scaleX = 1f
                scaleY = 1f
                setBackgroundResource(R.drawable.bg_bubble_delete_zone)
            }
            view = current
        } else {
            view = attachWindow(screenWidth, targetY) ?: return
        }
        isShowing = true

        view.animate().setListener(null).cancel()
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
        val view = rootView
        if (!isShowing || view == null || !view.isAttachedToWindow) {
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
                    animate().setListener(null).cancel()
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
                    animate().setListener(null).cancel()
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
        target.animate().setListener(null).cancel()
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
            isAttracted = false
            isLocked = false
            onComplete?.invoke()
            return
        }
        if (!isShowing && !view.isAttachedToWindow) {
            onComplete?.invoke()
            return
        }
        isShowing = false
        isAttracted = false
        isLocked = false

        if (!animated || !view.isAttachedToWindow) {
            cleanupView()
            onComplete?.invoke()
            return
        }

        view.animate().setListener(null).cancel()
        view.animate()
            .alpha(0f)
            .scaleX(0.5f)
            .scaleY(0.5f)
            .setDuration(180L)
            .withEndAction {
                if (rootView === view) {
                    cleanupView()
                } else {
                    try {
                        if (view.isAttachedToWindow) {
                            windowManager.removeView(view)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to remove old delete zone view on hide end", e)
                    }
                }
                onComplete?.invoke()
            }
            .start()
    }

    /**
     * Safely cleans up the root view and detaches it from WindowManager.
     */
    private fun cleanupView() {
        val view = rootView
        rootView = null
        val target = containerTarget
        containerTarget = null

        target?.animate()?.setListener(null)?.cancel()
        if (view != null) {
            view.animate().setListener(null).cancel()
            try {
                if (view.isAttachedToWindow) {
                    windowManager.removeView(view)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to remove delete zone view", e)
            }
        }
    }
}
