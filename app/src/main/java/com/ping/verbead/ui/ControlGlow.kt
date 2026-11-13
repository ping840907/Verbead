package com.ping.verbead.ui

import android.graphics.BlurMaskFilter
import android.graphics.Paint
import android.os.Build
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Sea Glass's signature coloured shadow for small controls (buttons, chips, switches).
 *
 * The glow is drawn behind the control's glass layer, inside a transparent margin of [ControlGlow.MARGIN_DP]
 * the layer has around the control. [inset] is that margin: the glow follows the control's capsule that
 * sits inset from the layer's edges. A transparent [color] (or an API < 28 device) draws nothing.
 */
internal fun Modifier.controlGlow(color: Color, inset: Dp): Modifier = drawWithCache {
    val blur = ControlGlow.BLUR_DP.dp.toPx()
    val offsetY = ControlGlow.OFFSET_DP.dp.toPx()
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    if (Build.VERSION.SDK_INT >= 28) paint.maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
    val i = inset.toPx()
    onDrawBehind {
        if (Build.VERSION.SDK_INT < 28 || color.alpha < 0.01f) return@onDrawBehind
        paint.color = color.toArgb()
        val w = size.width - 2 * i
        val h = size.height - 2 * i
        val r = minOf(w, h) / 2f
        drawIntoCanvas { it.nativeCanvas.drawRoundRect(i, i + offsetY, i + w, i + h + offsetY, r, r, paint) }
    }
}

internal object ControlGlow {
    /** Transparent room a control's glass layer keeps around the control for its glow. */
    const val MARGIN_DP = 10
    const val BLUR_DP = 6f
    const val OFFSET_DP = 2f

    /**
     * Lets the glow spill past the parents' bounds and padding. Stops at the scrolling container and at
     * [GlassCard] (which clips to its own rounded shape).
     */
    fun unclipAncestors(from: View) {
        var p = from.parent
        var depth = 0
        while (p is ViewGroup && depth < 6) {
            if (p is androidx.core.view.ScrollingView || p is android.widget.ScrollView || p is GlassCard || p.tag == "keepClip") break
            p.clipChildren = false
            p.clipToPadding = false
            p = p.parent
            depth++
        }
    }
}
