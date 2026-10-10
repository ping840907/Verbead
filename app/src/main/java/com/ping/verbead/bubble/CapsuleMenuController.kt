package com.ping.verbead.bubble

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.ping.verbead.R
import com.ping.verbead.util.HapticUtil
import kotlin.math.abs

/**
 * Controller for the vertical capsule menu overlay in FloatingBubbleService.
 * Handles menu view inflation, window management, animations, drag-follow physics,
 * item highlight feedback, and mode selection callbacks.
 */
class CapsuleMenuController(
    private val context: Context,
    private val windowManager: WindowManager,
    private val listener: Listener
) {

    interface Listener {
        fun onModeSelected(mode: Int)
        fun onDismissed()
    }

    companion object {
        private const val TAG = "CapsuleMenuController"

        const val MODE_VOICE = 0
        const val MODE_OCR = 1
        const val MODE_BARCODE = 2

        fun modeToIndex(mode: Int): Int = when (mode) {
            MODE_VOICE -> 0
            MODE_BARCODE -> 1
            MODE_OCR -> 2
            else -> 0
        }

        fun indexToMode(index: Int): Int = when (index) {
            0 -> MODE_VOICE
            1 -> MODE_BARCODE
            2 -> MODE_OCR
            else -> MODE_VOICE
        }
    }

    var isShowing: Boolean = false
        private set

    private var capsuleMenuView: View? = null
    private var capsuleLayoutParams: WindowManager.LayoutParams? = null
    private var capsuleInitialY: Float = 0f
    private var capsuleTouchStartY: Float = 0f
    private var currentHoveredIndex: Int = 0
    private var currentMode: Int = MODE_VOICE
    private var cachedMicCenterY: Float = 0f

    /**
     * Inflates and shows the capsule menu centered over the floating bubble.
     */
    fun show(
        bubbleX: Int,
        bubbleY: Int,
        bubbleWidthPx: Int,
        bubbleHeightPx: Int,
        mode: Int,
        startRawY: Float = 0f
    ) {
        if (isShowing) return

        val density = context.resources.displayMetrics.density
        currentMode = mode

        if (capsuleMenuView == null) {
            capsuleMenuView = LayoutInflater.from(context).inflate(R.layout.layout_capsule_menu, null)
        }
        val menu = capsuleMenuView ?: return

        val pill = menu.findViewById<LinearLayout>(R.id.layout_capsule_pill) ?: return
        val itemVoice = menu.findViewById<FrameLayout>(R.id.item_capsule_voice)
        val itemBarcode = menu.findViewById<FrameLayout>(R.id.item_capsule_barcode)
        val itemOcr = menu.findViewById<FrameLayout>(R.id.item_capsule_ocr)

        itemVoice?.setOnClickListener { selectMode(MODE_VOICE) }
        itemBarcode?.setOnClickListener { selectMode(MODE_BARCODE) }
        itemOcr?.setOnClickListener { selectMode(MODE_OCR) }

        val bubbleCenterY = bubbleY + (bubbleHeightPx / 2f)
        cachedMicCenterY = bubbleCenterY

        val currentIndex = modeToIndex(currentMode)
        currentHoveredIndex = currentIndex

        val windowWidth = bubbleWidthPx
        val windowHeight = (192 * density).toInt()

        // In layout_capsule_menu.xml:
        // Window padding top is 12dp. Pill height is 168dp (3 items * 56dp).
        // Center of item i within window: (12dp + 28dp + i * 56dp) = (40dp + i * 56dp)
        // To align item currentIndex directly over the bubble:
        // initialY + itemWinCenterY = bubbleCenterY
        // initialY = bubbleCenterY - itemWinCenterY
        val itemWinCenterY = (40f + currentIndex * 56f) * density
        val initialY = (bubbleCenterY - itemWinCenterY).toInt()
        capsuleInitialY = initialY.toFloat()
        capsuleTouchStartY = if (startRawY > 0f) startRawY else bubbleCenterY

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        capsuleLayoutParams = WindowManager.LayoutParams(
            windowWidth,
            windowHeight,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = bubbleX
            y = initialY
        }

        updateItemHighlights(currentIndex, animate = false)

        if (menu.isAttachedToWindow) {
            try {
                windowManager.removeView(menu)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to remove previous capsuleMenuView", e)
            }
        }

        try {
            windowManager.addView(menu, capsuleLayoutParams)
            isShowing = true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to add capsuleMenuView", e)
            return
        }

        pill.animate().cancel()
        pill.pivotX = 28f * density
        pill.pivotY = (28f + currentIndex * 56f) * density
        pill.scaleX = 0.5f
        pill.scaleY = 0.5f
        pill.alpha = 0f
        pill.animate()
            .scaleX(1.0f)
            .scaleY(1.0f)
            .alpha(1.0f)
            .setDuration(220)
            .setInterpolator(OvershootInterpolator(1.2f))
            .start()
    }

    /**
     * Updates capsule position and item highlighting during drag gesture.
     */
    fun updateDrag(rawX: Float, rawY: Float) {
        val menu = capsuleMenuView ?: return
        if (!isShowing || !menu.isAttachedToWindow) return
        val lp = capsuleLayoutParams ?: return

        val density = context.resources.displayMetrics.density
        val itemPitchPx = 56f * density
        val currentIndex = modeToIndex(currentMode)

        val dy = rawY - capsuleTouchStartY

        // When dragging up (dy < 0), items below (higher index) move up toward bubble
        // When dragging down (dy > 0), items above (lower index) move down toward bubble
        val maxUpDrag = -(2 - currentIndex) * itemPitchPx
        val maxDownDrag = currentIndex * itemPitchPx

        val effectiveDy = when {
            dy < maxUpDrag -> maxUpDrag + (dy - maxUpDrag) * 0.25f
            dy > maxDownDrag -> maxDownDrag + (dy - maxDownDrag) * 0.25f
            else -> dy
        }

        lp.y = (capsuleInitialY + effectiveDy).toInt()
        try {
            windowManager.updateViewLayout(menu, lp)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update capsuleMenuView layout", e)
        }

        // Find which item's screen center is closest to bubble center
        var closestIndex = currentIndex
        var minDistance = Float.MAX_VALUE
        for (i in 0..2) {
            val itemCenterOnScreen = lp.y + (40f + i * 56f) * density
            val dist = abs(itemCenterOnScreen - cachedMicCenterY)
            if (dist < minDistance) {
                minDistance = dist
                closestIndex = i
            }
        }

        if (closestIndex != currentHoveredIndex) {
            currentHoveredIndex = closestIndex
            HapticUtil.tick(context)
            updateItemHighlights(closestIndex, animate = true)
        }
    }

    /**
     * Handles finger release after drag gesture.
     */
    fun finishDrag(rawX: Float, rawY: Float) {
        val menu = capsuleMenuView ?: return
        if (!isShowing) return

        val targetMode = indexToMode(currentHoveredIndex)
        val isModeChanged = (targetMode != currentMode)
        if (isModeChanged) {
            HapticUtil.click(context)
        }

        val pill = menu.findViewById<LinearLayout>(R.id.layout_capsule_pill)
        if (pill != null) {
            val density = context.resources.displayMetrics.density
            pill.pivotX = 28f * density
            pill.pivotY = (28f + currentHoveredIndex * 56f) * density
            pill.animate()
                .scaleX(0.2f)
                .scaleY(0.2f)
                .alpha(0f)
                .setDuration(180)
                .setInterpolator(DecelerateInterpolator(1.5f))
                .withEndAction {
                    dismiss()
                    if (isModeChanged) {
                        listener.onModeSelected(targetMode)
                    }
                }
                .start()
        } else {
            dismiss()
            if (isModeChanged) {
                listener.onModeSelected(targetMode)
            }
        }
    }

    /**
     * Handles clicking directly on a menu item.
     */
    fun selectMode(mode: Int) {
        val targetIndex = modeToIndex(mode)
        val isModeChanged = (mode != currentMode)

        val menu = capsuleMenuView ?: run {
            dismiss()
            if (isModeChanged) {
                listener.onModeSelected(mode)
            }
            return
        }

        val pill = menu.findViewById<LinearLayout>(R.id.layout_capsule_pill)
        if (pill != null) {
            val density = context.resources.displayMetrics.density
            pill.pivotX = 28f * density
            pill.pivotY = (28f + targetIndex * 56f) * density
            pill.animate()
                .scaleX(0.2f)
                .scaleY(0.2f)
                .alpha(0f)
                .setDuration(180)
                .setInterpolator(DecelerateInterpolator(1.5f))
                .withEndAction {
                    dismiss()
                    if (isModeChanged) {
                        listener.onModeSelected(mode)
                    }
                }
                .start()
        } else {
            dismiss()
            if (isModeChanged) {
                listener.onModeSelected(mode)
            }
        }
    }

    /**
     * Dismisses the capsule menu view and notifies the listener.
     */
    fun dismiss() {
        val menu = capsuleMenuView
        if (!isShowing && (menu == null || !menu.isAttachedToWindow)) return
        isShowing = false

        menu?.findViewById<LinearLayout>(R.id.layout_capsule_pill)?.animate()?.cancel()

        if (menu != null && menu.isAttachedToWindow) {
            try {
                windowManager.removeView(menu)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to remove capsuleMenuView", e)
            }
        }
        listener.onDismissed()
    }

    /**
     * Completely cleans up resources when the service is destroyed.
     */
    fun destroy() {
        dismiss()
        capsuleMenuView = null
        capsuleLayoutParams = null
    }

    private fun updateItemHighlights(selectedIndex: Int, animate: Boolean) {
        val menu = capsuleMenuView ?: return
        val indicators = arrayOf(
            menu.findViewById<View>(R.id.indicator_capsule_voice),
            menu.findViewById<View>(R.id.indicator_capsule_barcode),
            menu.findViewById<View>(R.id.indicator_capsule_ocr)
        )
        val icons = arrayOf(
            menu.findViewById<ImageView>(R.id.iv_capsule_voice),
            menu.findViewById<ImageView>(R.id.iv_capsule_barcode),
            menu.findViewById<ImageView>(R.id.iv_capsule_ocr)
        )

        for (i in 0..2) {
            val isSelected = (i == selectedIndex)
            val targetIndAlpha = if (isSelected) 1.0f else 0.0f
            val targetIconAlpha = if (isSelected) 1.0f else 0.65f
            val targetIconScale = if (isSelected) 1.15f else 0.95f

            val ind = indicators[i]
            val iv = icons[i]

            if (animate) {
                ind?.animate()?.alpha(targetIndAlpha)?.setDuration(120)?.start()
                iv?.animate()?.alpha(targetIconAlpha)?.scaleX(targetIconScale)?.scaleY(targetIconScale)?.setDuration(120)?.start()
            } else {
                ind?.alpha = targetIndAlpha
                iv?.alpha = targetIconAlpha
                iv?.scaleX = targetIconScale
                iv?.scaleY = targetIconScale
            }
        }
    }
}
