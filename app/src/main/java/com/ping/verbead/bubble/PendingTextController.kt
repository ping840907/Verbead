package com.ping.verbead.bubble

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.ping.verbead.R
import com.ping.verbead.util.allowDisplayCutout

/**
 * Floating quick-fill list: recognized text (speech, OCR, barcode, quick phrase) that could not be filled because no
 * text field had focus stays beside the bubble. Several entries can wait at once (the user picks how many in the bubble
 * settings; 0 turns the list off). Tapping an entry fills it into the field that has focus now and removes it.
 * The newest entry is on top.
 */
class PendingTextController(
    private val context: Context,
    private val windowManager: WindowManager,
    private val onFill: (String) -> Boolean
) {
    private val items = ArrayList<String>() // newest first

    private var root: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null

    // last anchor, so the list can be rebuilt in place
    private var anchorY = 0
    private var dockedRight = true
    private var screenW = 0
    private var safeBottom = 0

    val isShowing: Boolean get() = root?.isAttachedToWindow == true

    val size: Int get() = items.size

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    /** Adds [text] on top (an identical entry moves up instead of repeating) and keeps at most [limit] entries. */
    fun add(text: String, limit: Int, anchorY: Int, isDockedOnRight: Boolean, screenWidth: Int, safeBottom: Int) {
        if (limit <= 0 || text.isBlank()) return
        items.remove(text)
        items.add(0, text)
        while (items.size > limit) items.removeAt(items.size - 1)
        this.anchorY = anchorY
        this.dockedRight = isDockedOnRight
        this.screenW = screenWidth
        this.safeBottom = safeBottom
        refresh()
    }

    /** Drops entries beyond [limit] (the setting was lowered); closes the list when nothing is left. */
    fun trim(limit: Int) {
        if (items.isEmpty()) return
        if (limit <= 0) { clear(); return }
        if (items.size > limit) {
            while (items.size > limit) items.removeAt(items.size - 1)
            refresh()
        }
    }

    fun clear() {
        items.clear()
        removeWindow()
    }

    fun updatePosition(anchorY: Int, isDockedOnRight: Boolean, screenWidth: Int, safeBottom: Int) {
        this.anchorY = anchorY
        this.dockedRight = isDockedOnRight
        this.screenW = screenWidth
        this.safeBottom = safeBottom
        applyPosition()
    }

    private fun refresh() {
        if (items.isEmpty()) {
            removeWindow()
            return
        }
        val card = root ?: buildRoot().also { root = it }
        card.removeAllViews()
        card.addView(buildHeader())
        for (text in items.toList()) card.addView(buildRow(text))
        if (!card.isAttachedToWindow) {
            try {
                windowManager.addView(card, params)
            } catch (_: Exception) {
            }
        }
        applyPosition()
    }

    private fun buildRoot(): LinearLayout {
        params = WindowManager.LayoutParams(
            dp(260), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply { allowDisplayCutout() }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#E6202124"))
                setStroke(dp(1), Color.parseColor("#66FFFFFF"))
            }
        }
    }

    private fun buildHeader(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(context).apply {
            setText(R.string.pending_text_title)
            setTextColor(Color.parseColor("#B0FFFFFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(closeButton(context.getString(R.string.pending_text_dismiss_all)) { clear() })
    }

    private fun buildRow(text: String): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(2), 0, dp(2))
        addView(TextView(context).apply {
            this.text = text
            contentDescription = context.getString(R.string.pending_text_fill)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#33FFFFFF"))
            }
            setOnClickListener {
                if (onFill(text)) {
                    items.remove(text)
                    refresh()
                } else {
                    Toast.makeText(context, R.string.pending_text_no_field, Toast.LENGTH_SHORT).show()
                }
            }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(closeButton(context.getString(R.string.pending_text_dismiss)) {
            items.remove(text)
            refresh()
        })
    }

    private fun closeButton(description: String, action: () -> Unit) = ImageButton(context).apply {
        setImageResource(R.drawable.ic_close)
        setColorFilter(Color.WHITE)
        setBackgroundColor(Color.TRANSPARENT)
        contentDescription = description
        setPadding(dp(6), dp(6), dp(6), dp(6))
        layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
        setOnClickListener { action() }
    }

    private fun applyPosition() {
        val p = params ?: return
        val r = root ?: return
        val bubbleW = dp(60)
        val gap = dp(8)
        val maxW = (screenW - bubbleW - gap - dp(12)).coerceAtLeast(dp(160))
        p.width = minOf(dp(260), maxW)
        p.gravity = if (dockedRight) Gravity.TOP or Gravity.RIGHT else Gravity.TOP or Gravity.LEFT
        p.x = bubbleW + gap
        r.measure(
            View.MeasureSpec.makeMeasureSpec(p.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val h = r.measuredHeight
        // the list grows downward from the bubble; when it would run off the bottom it moves up instead
        p.y = (anchorY - dp(8)).coerceIn(0, maxOf(0, safeBottom - h - dp(8)))
        if (r.isAttachedToWindow) windowManager.updateViewLayout(r, p)
    }

    private fun removeWindow() {
        val r = root ?: return
        if (r.isAttachedToWindow) {
            try {
                windowManager.removeView(r)
            } catch (_: Exception) {
            }
        }
    }

    fun destroy() {
        clear()
        root = null
    }
}
