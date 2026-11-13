package com.ping.verbead.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors

/**
 * A drawn soft keyboard for the onboarding mock screens: a suggestion strip, three rows of keys and a bottom
 * row with a space bar. Pure decoration, coloured from the active theme so it reads on any wallpaper scheme.
 */
class MockKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val path = Path()

    private fun themeColor(attr: Int, fallback: Int) = MaterialColors.getColor(this, attr, fallback)

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val body = themeColor(com.google.android.material.R.attr.colorSurfaceContainerHighest, 0xFFDDDDDD.toInt())
        val key = themeColor(com.google.android.material.R.attr.colorSurface, 0xFFFFFFFF.toInt())
        val hint = ColorUtils.setAlphaComponent(
            themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF666666.toInt()), 0x66
        )
        val accent = themeColor(androidx.appcompat.R.attr.colorPrimary, 0xFF3D6B4F.toInt())

        // Keyboard body, rounded at the top only.
        val r = 16f * density
        path.reset()
        path.addRoundRect(rect.apply { set(0f, 0f, w, h) }, floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f), Path.Direction.CW)
        paint.style = Paint.Style.FILL
        paint.color = body
        canvas.drawPath(path, paint)

        val pad = 8f * density
        val gap = 4f * density
        val keyR = 5f * density

        // Suggestion strip: three pills, the middle one picked out in the accent colour.
        val stripH = 16f * density
        val pillW = (w - 2 * pad - 2 * gap) / 3f
        for (i in 0 until 3) {
            val left = pad + i * (pillW + gap)
            rect.set(left, pad, left + pillW, pad + stripH)
            paint.color = if (i == 1) ColorUtils.setAlphaComponent(accent, 0x44) else ColorUtils.setAlphaComponent(key, 0xAA)
            canvas.drawRoundRect(rect, stripH / 2f, stripH / 2f, paint)
        }

        // Rows of keys. Each entry is a list of relative key widths.
        val rows = listOf(
            List(10) { 1f },
            List(9) { 1f },
            listOf(1.5f) + List(7) { 1f } + listOf(1.5f),
            listOf(1.4f, 1.1f, 5.2f, 1.1f, 1.6f)
        )
        val top = pad + stripH + gap + 2f * density
        val rowH = (h - top - pad - gap * (rows.size - 1)) / rows.size
        rows.forEachIndexed { rowIndex, widths ->
            val inset = if (rowIndex == 1) w * 0.025f else 0f
            val avail = w - 2 * pad - 2 * inset - gap * (widths.size - 1)
            val unit = avail / widths.sum()
            var x = pad + inset
            val y = top + rowIndex * (rowH + gap)
            widths.forEachIndexed { i, rel ->
                val kw = rel * unit
                rect.set(x, y, x + kw, y + rowH)
                // The wide keys of the third row and the last key are control keys: tinted a little.
                val control = (rowIndex == 2 && (i == 0 || i == widths.lastIndex)) || (rowIndex == 3 && (i == 0 || i == 1 || i == 4))
                paint.color = when {
                    rowIndex == 3 && i == 4 -> accent
                    control -> ColorUtils.blendARGB(key, body, 0.45f)
                    else -> key
                }
                canvas.drawRoundRect(rect, keyR, keyR, paint)
                // A faint glyph mark on letter keys.
                if (!control && rowIndex < 3) {
                    paint.color = hint
                    canvas.drawCircle(rect.centerX(), rect.centerY(), 1.6f * density, paint)
                }
                x += kw + gap
            }
        }
    }
}
