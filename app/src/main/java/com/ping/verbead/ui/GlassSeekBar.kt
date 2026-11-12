package com.ping.verbead.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.widget.AppCompatSeekBar
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.mttmcknn.seaglass.SeaGlassMaterialTheme
import dev.mttmcknn.seaglass.SeaGlassPalette
import dev.mttmcknn.seaglass.SeaGlassShape
import dev.mttmcknn.seaglass.rememberSeaGlassColorScheme
import dev.mttmcknn.seaglass.seaGlass

/**
 * SeekBar whose track and thumb are drawn as Sea Glass once [GlassSeekFrame] wraps it. The
 * SeekBar itself keeps all touch, keyboard and listener behaviour; its own drawables are made
 * invisible and the glass layer behind it follows the thumb position.
 */
class GlassSeekBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.appcompat.R.attr.seekBarStyle
) : AppCompatSeekBar(context, attrs, defStyleAttr) {

    internal val thumbCenterPx = mutableFloatStateOf(0f)
    internal val trackStartPx = mutableFloatStateOf(0f)
    internal val trackEndPx = mutableFloatStateOf(0f)
    internal val enabledState = mutableStateOf(true)
    internal val heightPx = mutableFloatStateOf(0f)
    internal var framed = false

    init {
        val d = resources.displayMetrics.density
        val clear = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setSize((THUMB_DP * d).toInt(), (THUMB_DP * d).toInt())
        }
        thumb = clear
        thumbOffset = 0
        progressDrawable = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setSize(1, (TRACK_DP * d).toInt())
        }
        splitTrack = false
        background = null
        minimumHeight = ((THUMB_DP + 8) * d).toInt()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val t = thumb ?: return
        val center = t.bounds.exactCenterX() + paddingLeft - thumbOffset
        if (center != thumbCenterPx.floatValue) thumbCenterPx.floatValue = center
        val s = paddingLeft + 0f
        val e = (width - paddingRight).toFloat()
        if (s != trackStartPx.floatValue) trackStartPx.floatValue = s
        if (e != trackEndPx.floatValue) trackEndPx.floatValue = e
        if (height.toFloat() != heightPx.floatValue) heightPx.floatValue = height.toFloat()
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        enabledState.value = enabled
    }

    override fun setVisibility(visibility: Int) {
        super.setVisibility(visibility)
        (parent as? GlassSeekFrame)?.visibility = visibility
    }

    internal companion object {
        const val THUMB_DP = 24
        const val TRACK_DP = 8
    }
}

/** Sea Glass layer behind a [GlassSeekBar]; sized to the bar, with room for the glow around it. */
class GlassSeekFrame internal constructor(
    context: Context,
    private val bar: GlassSeekBar
) : FrameLayout(context) {

    private val glow = (GLOW_DP * resources.displayMetrics.density).toInt()
    private val glassView: ComposeView

    init {
        clipChildren = false
        clipToPadding = false
        glassView = ComposeView(context).apply {
            setContent {
                val colors = rememberSeaGlassColorScheme(
                    dynamicColor = true,
                    darkTheme = isSystemInDarkTheme()
                )
                MaterialTheme(colorScheme = colors) {
                    val cs = MaterialTheme.colorScheme
                    val density = LocalDensity.current
                    val center by bar.thumbCenterPx
                    val start by bar.trackStartPx
                    val end by bar.trackEndPx
                    val h by bar.heightPx
                    val on by bar.enabledState
                    val trackH = GlassSeekBar.TRACK_DP.dp
                    val thumbD = GlassSeekBar.THUMB_DP.dp
                    val (startDp, endDp, centerDp, midDp) = with(density) {
                        listOf(start.toDp(), end.toDp(), center.toDp(), (h / 2f).toDp())
                    }
                    val trackBody = lerp(cs.surfaceVariant, cs.outline, .25f)
                    val trackPalette = SeaGlassPalette(
                        trackBody, lerp(trackBody, ComposeColor.White, .35f), cs.onSurface
                    )
                    val fillPalette = SeaGlassPalette(
                        cs.primary, lerp(cs.primary, ComposeColor.White, .35f), cs.onSurface
                    )
                    val thumbBody = lerp(cs.onPrimary, ComposeColor.White, .85f)
                    val thumbPalette = SeaGlassPalette(
                        thumbBody, lerp(thumbBody, ComposeColor.White, .35f), cs.onSurface
                    )
                    Box(Modifier.fillMaxSize().padding(GLOW_DP.dp).alpha(if (on) 1f else 0.45f)) {
                        SeaGlassMaterialTheme(style = SmallGlassRecipe, shape = SeaGlassShape.Capsule) {
                            Box(
                                Modifier.align(Alignment.TopStart)
                                    .offset(x = startDp, y = midDp - trackH / 2)
                                    .width((endDp - startDp).coerceAtLeast(0.dp))
                                    .height(trackH)
                                    .clip(RoundedCornerShape(50))
                                    .seaGlass(palette = trackPalette)
                            )
                            val fillW = (centerDp - startDp).coerceAtLeast(trackH)
                            Box(
                                Modifier.align(Alignment.TopStart)
                                    .offset(x = startDp, y = midDp - trackH / 2)
                                    .width(fillW)
                                    .height(trackH)
                                    .clip(RoundedCornerShape(50))
                                    .seaGlass(palette = fillPalette)
                            )
                        }
                        SeaGlassMaterialTheme(style = SmallGlassRecipe, shape = SeaGlassShape.Disc) {
                            Box(
                                Modifier.align(Alignment.TopStart)
                                    .offset(x = centerDp - thumbD / 2, y = midDp - thumbD / 2)
                                    .size(thumbD)
                                    .clip(CircleShape)
                                    .seaGlass(palette = thumbPalette)
                            )
                        }
                    }
                }
            }
        }
        addView(
            glassView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
                setMargins(-glow, -glow, -glow, -glow)
            }
        )
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        measureChildWithMargins(bar, widthMeasureSpec, 0, heightMeasureSpec, 0)
        val w = resolveSize(bar.measuredWidth, widthMeasureSpec)
        val h = resolveSize(bar.measuredHeight, heightMeasureSpec)
        setMeasuredDimension(w, h)
        glassView.measure(
            MeasureSpec.makeMeasureSpec(w + 2 * glow, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(h + 2 * glow, MeasureSpec.EXACTLY)
        )
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        bar.isEnabled = enabled
    }

    internal companion object {
        const val GLOW_DP = 3

        fun wrap(bar: GlassSeekBar) {
            if (bar.framed) return
            val parent = bar.parent as? ViewGroup ?: return
            val index = parent.indexOfChild(bar)
            val lp = bar.layoutParams
            val frame = GlassSeekFrame(bar.context, bar)
            parent.removeViewAt(index)
            frame.visibility = bar.visibility
            parent.addView(frame, index, lp)
            frame.addView(
                bar,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            )
            bar.framed = true
        }
    }
}
