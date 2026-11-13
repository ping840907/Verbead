package com.ping.verbead.bubble

import com.ping.verbead.util.isViewAdded
import android.animation.ValueAnimator
import android.content.Context
import com.ping.verbead.util.allowDisplayCutout
import android.graphics.PixelFormat
import android.text.TextUtils
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import com.ping.verbead.R

/** What the card is showing, which decides how an over-long text is handled. */
enum class PreviewKind {
    /** A state of the bubble (listening, loading ...): shown whole; a line that does not fit scrolls like a marquee. */
    STATUS,

    /** Text that keeps changing (the live recognition): the newest words matter, so the start is cut off. */
    LIVE
}

/** Pure decisions about how to lay out the card text (kept out of the view code so they can be tested). */
object PreviewTextPolicy {
    /** Lines a status text may wrap onto before it is scrolled instead. */
    const val STATUS_MAX_WRAP_LINES = 2
    const val LIVE_MAX_LINES = 3

    /** Marquee speed and the pauses at both ends. */
    const val MARQUEE_DP_PER_SECOND = 40f
    const val MARQUEE_START_DELAY_MS = 900L

    /** A status text scrolls when it would need more than [STATUS_MAX_WRAP_LINES] lines at [lineWidthPx]. */
    fun needsMarquee(textWidthPx: Float, lineWidthPx: Float): Boolean =
        lineWidthPx > 0f && textWidthPx > lineWidthPx * STATUS_MAX_WRAP_LINES

    /** How long one pass of the scroll takes for [overflowPx] of hidden text. */
    fun marqueeDurationMs(overflowPx: Float, density: Float): Long =
        ((overflowPx / (MARQUEE_DP_PER_SECOND * density)) * 1000f).toLong().coerceAtLeast(600L)
}

/**
 * The card next to the bubble. It only reports what the bubble is doing; it stays for as long as that state lasts
 * and is hidden by the flow that owns the state (there is no timer). Notices such as "copied" use [com.ping.verbead.util.ToastUtil].
 */
class BubblePreviewController(
    private val context: Context,
    private val windowManager: WindowManager,
    private val themedCtx: ContextThemeWrapper,
    @Suppress("UNUSED_PARAMETER") scope: kotlinx.coroutines.CoroutineScope
) {
    private lateinit var previewLayoutParams: WindowManager.LayoutParams
    lateinit var previewView: View
        private set
    private lateinit var cardBubblePreview: View
    private lateinit var clipView: FrameLayout
    private lateinit var tvBubblePreview: TextView
    private var marqueeAnimator: ValueAnimator? = null
    private var maxCardWidthPx = 0
    private var lastKind = PreviewKind.LIVE
    private var lastMessage = ""

    val isInitialized: Boolean
        get() = ::previewView.isInitialized

    val isAttachedToWindow: Boolean
        get() = ::previewView.isInitialized && previewView.isAttachedToWindow

    fun setup(bubbleWidthPx: Int, initialY: Int, density: Float) {
        previewView = LayoutInflater.from(themedCtx).inflate(R.layout.layout_floating_preview, null)
        cardBubblePreview = previewView.findViewById(R.id.card_bubble_preview)
        clipView = previewView.findViewById(R.id.fl_preview_text)
        tvBubblePreview = previewView.findViewById(R.id.tv_bubble_preview)

        val layoutFlag = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        previewLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            allowDisplayCutout()
            gravity = Gravity.TOP or Gravity.RIGHT
            x = bubbleWidthPx + (8 * density).toInt()
            y = initialY + (12 * density).toInt()
        }

        previewView.visibility = View.GONE
        windowManager.addView(previewView, previewLayoutParams)
    }

    fun refreshStyle() {
        if (::cardBubblePreview.isInitialized) {
            (cardBubblePreview as? com.ping.verbead.ui.GlassFrameLayout)?.refreshPanelStyle()
        }
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

        val newMax = maxOf((120 * density).toInt(), screenWidth - bubbleWidthPx - visualGap - padding)
        if (newMax != maxCardWidthPx) {
            maxCardWidthPx = newMax
            // the width changed (rotation, other screen): lay the current text out again
            if (previewView.visibility == View.VISIBLE && lastMessage.isNotEmpty()) applyText(lastMessage, lastKind)
        }

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
        kind: PreviewKind,
        anchorY: Int,
        isDockedOnRight: Boolean,
        screenWidth: Int,
        safeBottom: Int,
        onUntuck: () -> Unit
    ) {
        if (!::previewView.isInitialized) return
        onUntuck()
        updatePosition(anchorY, isDockedOnRight, screenWidth, safeBottom)
        // The same text again (a state that is re-announced) must not restart the scroll
        if (!(msg == lastMessage && kind == lastKind && previewView.visibility == View.VISIBLE)) {
            applyText(msg, kind)
        }
        if (previewView.isAttachedToWindow) {
            previewView.visibility = View.VISIBLE
        }
    }

    /** Sets [msg] and lays it out for [kind] within the current maximum card width. */
    private fun applyText(msg: String, kind: PreviewKind) {
        lastMessage = msg
        lastKind = kind
        stopMarquee()
        val tv = tvBubblePreview
        val innerWidth = maxCardWidthPx - tv.paddingLeft - tv.paddingRight
        tv.text = msg
        when (kind) {
            PreviewKind.LIVE -> {
                tv.setSingleLine(false)
                tv.setHorizontallyScrolling(false)
                tv.maxLines = PreviewTextPolicy.LIVE_MAX_LINES
                tv.ellipsize = TextUtils.TruncateAt.START
                tv.maxWidth = maxCardWidthPx
                tv.layoutParams = tv.layoutParams.apply { width = FrameLayout.LayoutParams.WRAP_CONTENT }
                clipView.layoutParams = clipView.layoutParams.apply { width = FrameLayout.LayoutParams.WRAP_CONTENT }
            }
            PreviewKind.STATUS -> {
                val textWidth = tv.paint.measureText(msg)
                if (PreviewTextPolicy.needsMarquee(textWidth, innerWidth.toFloat())) {
                    // one line, full width, scrolled back and forth inside a clipped box of the card width
                    tv.setSingleLine(true)
                    tv.setHorizontallyScrolling(true)
                    tv.ellipsize = null
                    tv.maxWidth = Int.MAX_VALUE
                    // The text view must really be as wide as the whole text (a wrap_content child of the fixed-width clip box
                    // would be cut to the box width, and the scrolled text would run out before the end).
                    val contentWidth = kotlin.math.ceil(textWidth + tv.paddingLeft + tv.paddingRight).toInt()
                    tv.layoutParams = tv.layoutParams.apply { width = contentWidth }
                    clipView.layoutParams = clipView.layoutParams.apply { width = maxCardWidthPx }
                    startMarquee(contentWidth.toFloat())
                } else {
                    tv.setSingleLine(false)
                    tv.setHorizontallyScrolling(false)
                    tv.maxLines = PreviewTextPolicy.STATUS_MAX_WRAP_LINES
                    tv.ellipsize = TextUtils.TruncateAt.END
                    tv.maxWidth = maxCardWidthPx
                    tv.layoutParams = tv.layoutParams.apply { width = FrameLayout.LayoutParams.WRAP_CONTENT }
                    clipView.layoutParams = clipView.layoutParams.apply { width = FrameLayout.LayoutParams.WRAP_CONTENT }
                }
            }
        }
        clipView.requestLayout()
    }

    private fun startMarquee(contentWidthPx: Float) {
        val overflow = contentWidthPx - maxCardWidthPx
        if (overflow <= 0f) return
        val density = context.resources.displayMetrics.density
        tvBubblePreview.translationX = 0f
        marqueeAnimator = ValueAnimator.ofFloat(0f, -overflow).apply {
            duration = PreviewTextPolicy.marqueeDurationMs(overflow, density)
            startDelay = PreviewTextPolicy.MARQUEE_START_DELAY_MS
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { tvBubblePreview.translationX = it.animatedValue as Float }
            start()
        }
    }

    private fun stopMarquee() {
        marqueeAnimator?.cancel()
        marqueeAnimator = null
        if (::tvBubblePreview.isInitialized) tvBubblePreview.translationX = 0f
    }

    fun hide() {
        stopMarquee()
        lastMessage = ""
        if (::previewView.isInitialized && previewView.isAttachedToWindow) {
            previewView.visibility = View.GONE
        }
    }

    fun destroy() {
        stopMarquee()
        if (::previewView.isInitialized && windowManager.isViewAdded(previewView)) {
            try {
                windowManager.removeView(previewView)
            } catch (_: Exception) {}
        }
    }
}
