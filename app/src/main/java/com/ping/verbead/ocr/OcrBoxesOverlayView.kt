package com.ping.verbead.ocr

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Overlay view that renders detected PP-OCR bounding boxes on top of the frozen camera snapshot.
 * Supports multi-selection with numbered order badges, select-all, and double-tap quick confirm.
 */
class OcrBoxesOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class TextBoundingBox(
        val originalRect: RectF,
        var displayRect: RectF = RectF(),
        val confidence: Float = 1.0f
    )

    private val boxes = mutableListOf<TextBoundingBox>()
    // Preserves selection sequence (order of selection: 1st, 2nd, 3rd...)
    private val selectedIndices = mutableListOf<Int>()

    var onSelectionChanged: ((List<Int>) -> Unit)? = null
    var onQuickConfirm: ((TextBoundingBox, Int) -> Unit)? = null

    private var lastTapTime = 0L
    private var lastTappedIndex = -1

    private val density = resources.displayMetrics.density

    private val boxBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = 0xFF64B5F6.toInt() // Light blue
    }

    private val boxFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x2664B5F6 // Translucent light blue
    }

    private val selectedBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        color = 0xFF4CAF50.toInt() // Vivid green
    }

    private val selectedFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x4D4CAF50 // Translucent green
    }

    private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF4CAF50.toInt()
    }

    private val badgeStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = 0xFFFFFFFF.toInt()
    }

    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = 11f * density
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }

    fun setBoxes(newBoxes: List<TextBoundingBox>) {
        boxes.clear()
        boxes.addAll(newBoxes)
        selectedIndices.clear()
        invalidate()
        onSelectionChanged?.invoke(emptyList())
    }

    fun setDetectedBoxes(rects: List<RectF>, bitmapWidth: Int, bitmapHeight: Int) {
        post {
            val vw = width
            val vh = height
            if (vw <= 0 || vh <= 0 || bitmapWidth <= 0 || bitmapHeight <= 0) return@post
            val scale = minOf(vw.toFloat() / bitmapWidth, vh.toFloat() / bitmapHeight)
            val dw = bitmapWidth * scale
            val dh = bitmapHeight * scale
            val dx = (vw - dw) / 2f
            val dy = (vh - dh) / 2f

            val mapped = rects.map { r ->
                val disp = RectF(
                    dx + r.left * scale,
                    dy + r.top * scale,
                    dx + r.right * scale,
                    dy + r.bottom * scale
                )
                TextBoundingBox(r, disp)
            }
            setBoxes(mapped)
        }
    }

    fun clearBoxes() {
        boxes.clear()
        selectedIndices.clear()
        invalidate()
        onSelectionChanged?.invoke(emptyList())
    }

    fun getSelectedBoxes(): List<TextBoundingBox> =
        selectedIndices.mapNotNull { boxes.getOrNull(it) }

    fun getSelectedIndices(): List<Int> = selectedIndices.toList()

    /**
     * Selects all detected boxes in natural reading order.
     * For predominantly vertical columns: sorted right-to-left, top-to-bottom.
     * For horizontal lines: sorted top-to-bottom, left-to-right.
     */
    fun selectAll() {
        if (boxes.isEmpty()) return
        val verticalCount = boxes.count { it.originalRect.height() > it.originalRect.width() * 1.1f }
        val isMostlyVertical = verticalCount > boxes.size / 2

        val sorted = boxes.indices.sortedWith { i1, i2 ->
            val r1 = boxes[i1].originalRect
            val r2 = boxes[i2].originalRect
            if (isMostlyVertical) {
                // Group columns by X position band (right to left)
                val colBand1 = (r1.right / 35).toInt()
                val colBand2 = (r2.right / 35).toInt()
                if (colBand1 != colBand2) {
                    colBand2.compareTo(colBand1) // Right to left
                } else {
                    r1.top.compareTo(r2.top) // Top to bottom within column
                }
            } else {
                // Group rows by Y position band (top to bottom)
                val rowBand1 = (r1.top / 25).toInt()
                val rowBand2 = (r2.top / 25).toInt()
                if (rowBand1 != rowBand2) {
                    rowBand1.compareTo(rowBand2) // Top to bottom
                } else {
                    r1.left.compareTo(r2.left) // Left to right within row
                }
            }
        }
        selectedIndices.clear()
        selectedIndices.addAll(sorted)
        invalidate()
        onSelectionChanged?.invoke(selectedIndices.toList())
    }

    fun clearSelection() {
        selectedIndices.clear()
        invalidate()
        onSelectionChanged?.invoke(emptyList())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val badgeRadius = 10f * density
        val textYOffset = (badgeTextPaint.descent() + badgeTextPaint.ascent()) / 2f

        for (i in boxes.indices) {
            val box = boxes[i]
            val selectionOrder = selectedIndices.indexOf(i)
            val isSelected = (selectionOrder >= 0)
            val rect = box.displayRect

            val fill = if (isSelected) selectedFillPaint else boxFillPaint
            val border = if (isSelected) selectedBorderPaint else boxBorderPaint

            canvas.drawRoundRect(rect, 6f * density, 6f * density, fill)
            canvas.drawRoundRect(rect, 6f * density, 6f * density, border)

            if (isSelected) {
                // Draw numbered sequence badge at top-left corner
                val badgeX = max(badgeRadius + 2f * density, min(width - badgeRadius - 2f * density, rect.left + badgeRadius))
                val badgeY = max(badgeRadius + 2f * density, min(height - badgeRadius - 2f * density, rect.top + badgeRadius))

                canvas.drawCircle(badgeX, badgeY, badgeRadius, badgeBgPaint)
                canvas.drawCircle(badgeX, badgeY, badgeRadius, badgeStrokePaint)
                canvas.drawText("${selectionOrder + 1}", badgeX, badgeY - textYOffset, badgeTextPaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            val touchX = event.x
            val touchY = event.y

            // Touch target with padding for comfortable tapping
            val pad = 8f * density
            val hitIndex = boxes.indexOfFirst { box ->
                val r = box.displayRect
                touchX >= r.left - pad && touchX <= r.right + pad &&
                touchY >= r.top - pad && touchY <= r.bottom + pad
            }

            if (hitIndex >= 0) {
                val now = SystemClock.uptimeMillis()
                val isDoubleTap = (hitIndex == lastTappedIndex && now - lastTapTime < 350)
                lastTapTime = now
                lastTappedIndex = hitIndex

                if (isDoubleTap) {
                    // Double tap: immediately quick confirm this single box
                    selectedIndices.clear()
                    selectedIndices.add(hitIndex)
                    invalidate()
                    onSelectionChanged?.invoke(selectedIndices.toList())
                    onQuickConfirm?.invoke(boxes[hitIndex], hitIndex)
                    return true
                }

                // Single tap: toggle selection
                if (selectedIndices.contains(hitIndex)) {
                    selectedIndices.remove(hitIndex)
                } else {
                    selectedIndices.add(hitIndex)
                }
                invalidate()
                onSelectionChanged?.invoke(selectedIndices.toList())
                return true
            }
        }
        return true
    }
}
