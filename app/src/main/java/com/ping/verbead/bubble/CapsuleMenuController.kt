package com.ping.verbead.bubble

import android.content.Context
import android.graphics.PixelFormat
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
        const val MODE_PHRASES = 3
        const val MODE_SLM = 4

        const val ITEM_COUNT = 5
        const val ITEM_HEIGHT_DP = 56
        const val CAPSULE_MENU_HEIGHT_DP = ITEM_COUNT * ITEM_HEIGHT_DP // 280dp
        const val CAPSULE_WINDOW_HEIGHT_DP = 304 // 280dp + 12dp top + 12dp bottom padding
        // 懸浮球中心至膠囊視窗頂/底之最大外推距離：
        // 視窗邊界 304dp，氣泡中心 30dp。SLM 模式下項目中心在 264dp，往上延伸至視窗頂為 264dp - 30dp = 234dp；
        // Voice 模式下項目中心在 40dp，往下延伸至視窗底為 (304dp - 40dp) - 30dp = 234dp。
        const val CAPSULE_MENU_MAX_EXTENT_DP = 234

        fun computeCapsuleMenuHeightDp(itemCount: Int): Int =
            itemCount.coerceIn(1, ITEM_COUNT) * ITEM_HEIGHT_DP

        fun computeCapsuleWindowHeightDp(itemCount: Int): Int =
            computeCapsuleMenuHeightDp(itemCount) + 24

        fun modeToIndex(
            mode: Int,
            activeModes: List<Int> = com.ping.verbead.engine.ModelConfig.DEFAULT_BUBBLE_MODE_ORDER
        ): Int {
            val idx = activeModes.indexOf(mode)
            return if (idx >= 0) idx else 0
        }

        fun indexToMode(
            index: Int,
            activeModes: List<Int> = com.ping.verbead.engine.ModelConfig.DEFAULT_BUBBLE_MODE_ORDER
        ): Int {
            return activeModes.getOrElse(index) {
                activeModes.firstOrNull() ?: MODE_VOICE
            }
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
    private var cachedMinY: Int = 0
    private var cachedMaxY: Int = Int.MAX_VALUE
    private var activeModes: List<Int> = com.ping.verbead.engine.ModelConfig.DEFAULT_BUBBLE_MODE_ORDER

    private var modeItemViews: Map<Int, FrameLayout> = emptyMap()
    private var modeIndicatorViews: Map<Int, View> = emptyMap()
    private var modeIconViews: Map<Int, ImageView> = emptyMap()

    /**
     * Inflates and shows the capsule menu centered over the floating bubble.
     */
    fun show(
        bubbleX: Int,
        bubbleY: Int,
        bubbleWidthPx: Int,
        bubbleHeightPx: Int,
        mode: Int,
        startRawY: Float = 0f,
        minY: Int = 0,
        maxY: Int = Int.MAX_VALUE,
        screenWidth: Int = Int.MAX_VALUE
    ) {
        if (isShowing) return

        cachedMinY = minY
        cachedMaxY = maxY

        val density = context.resources.displayMetrics.density
        activeModes = com.ping.verbead.engine.ModelConfig.getEnabledBubbleModes(context)
        currentMode = if (mode in activeModes) mode else (activeModes.firstOrNull() ?: MODE_VOICE)

        if (capsuleMenuView == null) {
            val inflated = LayoutInflater.from(context).inflate(R.layout.layout_capsule_menu, null)
            capsuleMenuView = inflated

            val itemVoice = inflated.findViewById<FrameLayout>(R.id.item_capsule_voice)
            val itemBarcode = inflated.findViewById<FrameLayout>(R.id.item_capsule_barcode)
            val itemOcr = inflated.findViewById<FrameLayout>(R.id.item_capsule_ocr)
            val itemPhrases = inflated.findViewById<FrameLayout>(R.id.item_capsule_phrases)
            val itemSlm = inflated.findViewById<FrameLayout>(R.id.item_capsule_slm)

            itemVoice?.setOnClickListener { selectMode(MODE_VOICE) }
            itemBarcode?.setOnClickListener { selectMode(MODE_BARCODE) }
            itemOcr?.setOnClickListener { selectMode(MODE_OCR) }
            itemPhrases?.setOnClickListener { selectMode(MODE_PHRASES) }
            itemSlm?.setOnClickListener { selectMode(MODE_SLM) }

            modeItemViews = buildMap {
                itemVoice?.let { put(MODE_VOICE, it) }
                itemBarcode?.let { put(MODE_BARCODE, it) }
                itemOcr?.let { put(MODE_OCR, it) }
                itemPhrases?.let { put(MODE_PHRASES, it) }
                itemSlm?.let { put(MODE_SLM, it) }
            }
            modeIndicatorViews = buildMap {
                inflated.findViewById<View>(R.id.indicator_capsule_voice)?.let { put(MODE_VOICE, it) }
                inflated.findViewById<View>(R.id.indicator_capsule_barcode)?.let { put(MODE_BARCODE, it) }
                inflated.findViewById<View>(R.id.indicator_capsule_ocr)?.let { put(MODE_OCR, it) }
                inflated.findViewById<View>(R.id.indicator_capsule_phrases)?.let { put(MODE_PHRASES, it) }
                inflated.findViewById<View>(R.id.indicator_capsule_slm)?.let { put(MODE_SLM, it) }
            }
            modeIconViews = buildMap {
                inflated.findViewById<ImageView>(R.id.iv_capsule_voice)?.let { put(MODE_VOICE, it) }
                inflated.findViewById<ImageView>(R.id.iv_capsule_barcode)?.let { put(MODE_BARCODE, it) }
                inflated.findViewById<ImageView>(R.id.iv_capsule_ocr)?.let { put(MODE_OCR, it) }
                inflated.findViewById<ImageView>(R.id.iv_capsule_phrases)?.let { put(MODE_PHRASES, it) }
                inflated.findViewById<ImageView>(R.id.iv_capsule_slm)?.let { put(MODE_SLM, it) }
            }
        }
        val menu = capsuleMenuView ?: return
        val pill = menu.findViewById<LinearLayout>(R.id.layout_capsule_pill) ?: return

        // Dynamically populate enabled modes in the user's custom order
        pill.removeAllViews()
        for (m in activeModes) {
            modeItemViews[m]?.let { itemView ->
                (itemView.parent as? android.view.ViewGroup)?.removeView(itemView)
                pill.addView(itemView)
            }
        }

        val activeCount = activeModes.size.coerceAtLeast(1)
        val pillHeightPx = (computeCapsuleMenuHeightDp(activeCount) * density).toInt()
        pill.layoutParams = pill.layoutParams?.apply {
            height = pillHeightPx
        }

        val bubbleCenterY = bubbleY + (bubbleHeightPx / 2f)
        cachedMicCenterY = bubbleCenterY

        val currentIndex = modeToIndex(currentMode, activeModes)
        currentHoveredIndex = currentIndex

        val windowWidth = bubbleWidthPx
        val windowHeight = (computeCapsuleWindowHeightDp(activeCount) * density).toInt()

        // Center of item i within window: (12dp + 28dp + i * 56dp) = (40dp + i * 56dp)
        // To align item currentIndex directly over the bubble:
        // initialY + itemWinCenterY = bubbleCenterY => initialY = bubbleCenterY - itemWinCenterY
        val itemWinCenterY = (40f + currentIndex * 56f) * density
        val initialY = (bubbleCenterY - itemWinCenterY).toInt()
        val maxAllowedY = maxOf(minY, maxY - windowHeight)
        val clampedInitialY = initialY.coerceIn(minY, maxAllowedY)
        capsuleInitialY = clampedInitialY.toFloat()
        capsuleTouchStartY = if (startRawY > 0f) startRawY else bubbleCenterY

        val layoutFlag = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

        val clampedX = bubbleX.coerceIn(0, maxOf(0, screenWidth - windowWidth))
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
            x = clampedX
            y = clampedInitialY
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
        val activeCount = activeModes.size.coerceAtLeast(1)
        val currentIndex = modeToIndex(currentMode, activeModes)

        val dy = rawY - capsuleTouchStartY

        // When dragging up (dy < 0), items below (higher index) move up toward bubble
        // When dragging down (dy > 0), items above (lower index) move down toward bubble
        val maxUpDrag = -((activeCount - 1) - currentIndex) * itemPitchPx
        val maxDownDrag = currentIndex * itemPitchPx

        val effectiveDy = when {
            dy < maxUpDrag -> maxUpDrag + (dy - maxUpDrag) * 0.25f
            dy > maxDownDrag -> maxDownDrag + (dy - maxDownDrag) * 0.25f
            else -> dy
        }

        val maxAllowedY = maxOf(cachedMinY, cachedMaxY - lp.height)
        lp.y = (capsuleInitialY + effectiveDy).toInt().coerceIn(cachedMinY, maxAllowedY)
        try {
            windowManager.updateViewLayout(menu, lp)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update capsuleMenuView layout", e)
        }

        // Find which item's screen center is closest to bubble center
        var closestIndex = currentIndex
        var minDistance = Float.MAX_VALUE
        for (i in 0 until activeCount) {
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

        val targetMode = indexToMode(currentHoveredIndex, activeModes)
        val isModeChanged = (targetMode != currentMode)
        val shouldNotify = isModeChanged || (targetMode != MODE_VOICE)
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
                    if (shouldNotify) {
                        listener.onModeSelected(targetMode)
                    }
                }
                .start()
        } else {
            dismiss()
            if (shouldNotify) {
                listener.onModeSelected(targetMode)
            }
        }
    }

    /**
     * Handles clicking directly on a menu item.
     */
    fun selectMode(mode: Int) {
        val targetIndex = modeToIndex(mode, activeModes)
        HapticUtil.click(context)

        val menu = capsuleMenuView ?: run {
            dismiss()
            listener.onModeSelected(mode)
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
                    listener.onModeSelected(mode)
                }
                .start()
        } else {
            dismiss()
            listener.onModeSelected(mode)
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
        modeItemViews = emptyMap()
        modeIndicatorViews = emptyMap()
        modeIconViews = emptyMap()
    }

    private fun updateItemHighlights(selectedIndex: Int, animate: Boolean) {
        for ((i, mode) in activeModes.withIndex()) {
            val isSelected = (i == selectedIndex)
            val targetIndAlpha = if (isSelected) 1.0f else 0.0f
            val targetIconAlpha = if (isSelected) 1.0f else 0.65f
            val targetIconScale = if (isSelected) 1.15f else 0.95f

            val ind = modeIndicatorViews[mode]
            val iv = modeIconViews[mode]

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
