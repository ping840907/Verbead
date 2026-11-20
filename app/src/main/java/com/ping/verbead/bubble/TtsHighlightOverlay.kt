package com.ping.verbead.bubble

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.ping.verbead.tts.ScreenLine
import com.ping.verbead.util.allowDisplayCutout
import com.ping.verbead.util.isViewAdded

/**
 * A full-screen layer under the player that marks the line being read, and, in "pick" mode, outlines every
 * line so the user can tap the one to start from. Outside pick mode it does not take touches, so the app
 * underneath stays usable.
 */
class TtsHighlightOverlay(
    private val context: Context,
    private val windowManager: WindowManager
) {
    private var view: LinesView? = null
    private var params: WindowManager.LayoutParams? = null

    val isShowing: Boolean get() = view != null
    var isPicking: Boolean = false
        private set

    /**
     * Adds the layer. Call it before the player's own window is added, so the player stays on top of it
     * (windows stack in the order they were added).
     */
    fun attach() {
        if (view != null) return
        val v = LinesView(context)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            FLAGS_PASS_THROUGH,
            PixelFormat.TRANSLUCENT
        ).apply {
            allowDisplayCutout()
            gravity = Gravity.TOP or Gravity.LEFT
            x = 0
            y = 0
        }
        try {
            windowManager.addView(v, lp)
            view = v
            params = lp
        } catch (e: Exception) {
            Log.w(TAG, "Failed to add highlight overlay", e)
        }
    }

    /** The lines the user can pick from; empty clears everything (and ends a pick). */
    fun setLines(lines: List<ScreenLine>) {
        val v = view ?: return
        if (lines.isEmpty() && isPicking) stopPick()
        v.lines = lines
        if (lines.isEmpty()) v.current = null
        v.invalidate()
    }

    /** Marks the lines of the sentence being read, as one box around them; none clears the mark. */
    fun highlight(lines: List<ScreenLine>) {
        val v = view ?: return
        v.current = if (lines.isEmpty()) null else ScreenLine(
            lines.minOf { it.left }, lines.minOf { it.top }, lines.maxOf { it.right }, lines.maxOf { it.bottom }, ""
        )
        v.invalidate()
    }

    /** Outlines every line; a tap on one calls [onPick] with its index, a tap elsewhere calls [onCancel]. */
    fun startPick(onPick: (Int) -> Unit, onCancel: () -> Unit) {
        val v = view ?: return
        val lp = params ?: return
        if (v.lines.isEmpty()) return
        isPicking = true
        v.picking = true
        v.onPick = { index ->
            stopPick()
            onPick(index)
        }
        v.onCancel = {
            stopPick()
            onCancel()
        }
        lp.flags = FLAGS_TOUCHABLE
        if (windowManager.isViewAdded(v)) runCatching { windowManager.updateViewLayout(v, lp) }
        v.invalidate()
    }

    fun stopPick() {
        val v = view ?: return
        val lp = params ?: return
        isPicking = false
        v.picking = false
        v.onPick = null
        v.onCancel = null
        lp.flags = FLAGS_PASS_THROUGH
        if (windowManager.isViewAdded(v)) runCatching { windowManager.updateViewLayout(v, lp) }
        v.invalidate()
    }

    fun detach() {
        val v = view ?: return
        view = null
        params = null
        isPicking = false
        if (windowManager.isViewAdded(v)) runCatching { windowManager.removeView(v) }
    }

    private class LinesView(context: Context) : View(context) {
        var lines: List<ScreenLine> = emptyList()
        var current: ScreenLine? = null
        var picking = false
        var onPick: ((Int) -> Unit)? = null
        var onCancel: (() -> Unit)? = null

        private val density = resources.displayMetrics.density
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val origin = IntArray(2)
        private val rect = RectF()

        override fun onDraw(canvas: Canvas) {
            // the window may start below the status bar or a cutout: line positions are in screen pixels
            getLocationOnScreen(origin)
            val dx = -origin[0].toFloat()
            val dy = -origin[1].toFloat()
            val r = 6 * density
            if (picking) {
                fill.color = Color.argb(28, 100, 181, 246)
                stroke.color = Color.argb(210, 100, 181, 246)
                stroke.strokeWidth = 1.5f * density
                for (line in lines) {
                    rect.set(line.left + dx, line.top + dy, line.right + dx, line.bottom + dy)
                    canvas.drawRoundRect(rect, r, r, fill)
                    canvas.drawRoundRect(rect, r, r, stroke)
                }
            }
            current?.let { line ->
                val pad = 3 * density
                rect.set(line.left + dx - pad, line.top + dy - pad, line.right + dx + pad, line.bottom + dy + pad)
                fill.color = Color.argb(70, 255, 213, 79)
                stroke.color = Color.argb(230, 255, 193, 7)
                stroke.strokeWidth = 2.5f * density
                canvas.drawRoundRect(rect, r, r, fill)
                canvas.drawRoundRect(rect, r, r, stroke)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!picking) return false
            if (event.actionMasked == MotionEvent.ACTION_DOWN) return true
            if (event.actionMasked != MotionEvent.ACTION_UP) return true
            getLocationOnScreen(origin)
            val x = event.x + origin[0]
            val y = event.y + origin[1]
            val slop = 10 * density
            // the smallest box that holds the tap wins, so a line inside a bigger block is still reachable
            var best = -1
            var bestArea = Long.MAX_VALUE
            for ((i, line) in lines.withIndex()) {
                if (x >= line.left - slop && x <= line.right + slop && y >= line.top - slop && y <= line.bottom + slop) {
                    val area = (line.right - line.left).toLong() * (line.bottom - line.top)
                    if (area < bestArea) { best = i; bestArea = area }
                }
            }
            if (best >= 0) onPick?.invoke(best) else onCancel?.invoke()
            return true
        }
    }

    private companion object {
        const val TAG = "TtsHighlightOverlay"
        const val FLAGS_PASS_THROUGH = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        const val FLAGS_TOUCHABLE = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
    }
}
