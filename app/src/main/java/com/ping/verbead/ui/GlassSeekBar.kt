package com.ping.verbead.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.widget.AppCompatSeekBar
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.ping.verbead.ui.style.ExpressivePalette
import com.ping.verbead.ui.style.UiStyle
import com.ping.verbead.ui.style.UiStyleManager
import dev.mttmcknn.seaglass.SeaGlassMaterialTheme
import dev.mttmcknn.seaglass.SeaGlassPalette
import dev.mttmcknn.seaglass.SeaGlassShape
import dev.mttmcknn.seaglass.rememberSeaGlassColorScheme
import dev.mttmcknn.seaglass.seaGlass

/**
 * SeekBar whose track and thumb are drawn by [GlassSeekFrame] in the active [UiStyle]:
 * - [UiStyle.EXPRESSIVE]: thick 14dp split-gap track with stop dot and morphing vertical bar handle.
 * - [UiStyle.SEA_GLASS]: frosted glass capsule track and bouncy disc thumb.
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
    internal val heldState = mutableStateOf(false)
    /** Overlay windows float over other apps: always the dark scheme. */
    internal val forceDark: Boolean = run {
        if (attrs == null) false else {
            val a = context.obtainStyledAttributes(attrs, com.ping.verbead.R.styleable.GlassOverlay)
            val v = a.getBoolean(com.ping.verbead.R.styleable.GlassOverlay_glassDark, false)
            a.recycle()
            v
        }
    }

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
        minimumHeight = ((THUMB_DP + 14) * d).toInt()
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

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> heldState.value = true
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> heldState.value = false
        }
        return super.onTouchEvent(event)
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

/** Themed layer behind a [GlassSeekBar]; sized to the bar, with room for the glow/handle around it. */
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
            visibility = GONE
            setContent {
                val style = UiStyleManager.currentStyle(context)
                val dark = isSystemInDarkTheme() || bar.forceDark
                val colors = rememberSeaGlassColorScheme(
                    dynamicColor = true,
                    darkTheme = dark
                )
                MaterialTheme(colorScheme = colors) {
                    val cs = MaterialTheme.colorScheme
                    val density = LocalDensity.current
                    val center by bar.thumbCenterPx
                    val start by bar.trackStartPx
                    val end by bar.trackEndPx
                    val h by bar.heightPx
                    val on by bar.enabledState
                    val held by bar.heldState
                    val (startDp, endDp, centerDp, midDp) = with(density) {
                        listOf(start.toDp(), end.toDp(), center.toDp(), (h / 2f).toDp())
                    }

                    if (style == UiStyle.EXPRESSIVE) {
                        val trackH = 14.dp
                        val handleW by animateDpAsState(
                            targetValue = if (held) 4.dp else 6.dp,
                            animationSpec = spring(dampingRatio = 0.6f, stiffness = 550f),
                            label = "m3e-seek-w"
                        )
                        val handleH by animateDpAsState(
                            targetValue = if (held) 32.dp else 26.dp,
                            animationSpec = spring(dampingRatio = 0.55f, stiffness = 520f),
                            label = "m3e-seek-h"
                        )
                        val gapHalf = 6.dp + handleW / 2
                        val activeColor = if (bar.forceDark) ExpressivePalette.Overlay.sliderTrackActive else cs.primary
                        val inactiveColor = if (bar.forceDark) ExpressivePalette.Overlay.sliderTrackInactive else cs.secondaryContainer
                        val activeWidth = ((centerDp - gapHalf) - startDp).coerceAtLeast(0.dp)
                        val inactiveStart = (centerDp + gapHalf).coerceAtLeast(startDp)
                        val inactiveWidth = (endDp - inactiveStart).coerceAtLeast(0.dp)

                        Box(Modifier.fillMaxSize().padding(GLOW_DP.dp).alpha(if (on) 1f else 0.42f)) {
                            // Left active track segment
                            if (activeWidth > 1.dp) {
                                Box(
                                    Modifier
                                        .align(Alignment.TopStart)
                                        .offset(x = startDp, y = midDp - trackH / 2)
                                        .width(activeWidth)
                                        .height(trackH)
                                        .clip(
                                            RoundedCornerShape(
                                                topStart = 7.dp,
                                                bottomStart = 7.dp,
                                                topEnd = 3.dp,
                                                bottomEnd = 3.dp
                                            )
                                        )
                                        .background(activeColor)
                                )
                            }
                            // Right inactive track segment
                            if (inactiveWidth > 1.dp) {
                                Box(
                                    Modifier
                                        .align(Alignment.TopStart)
                                        .offset(x = inactiveStart, y = midDp - trackH / 2)
                                        .width(inactiveWidth)
                                        .height(trackH)
                                        .clip(
                                            RoundedCornerShape(
                                                topStart = 3.dp,
                                                bottomStart = 3.dp,
                                                topEnd = 7.dp,
                                                bottomEnd = 7.dp
                                            )
                                        )
                                        .background(inactiveColor)
                                )
                                // Stop dot indicator at track end
                                if (inactiveWidth > 10.dp) {
                                    Box(
                                        Modifier
                                            .align(Alignment.TopStart)
                                            .offset(x = endDp - 7.dp, y = midDp - 2.dp)
                                            .size(4.dp)
                                            .clip(CircleShape)
                                            .background(activeColor.copy(alpha = 0.7f))
                                    )
                                }
                            }
                            // Expressive vertical pill handle
                            Box(
                                Modifier
                                    .align(Alignment.TopStart)
                                    .offset(x = centerDp - handleW / 2, y = midDp - handleH / 2)
                                    .size(width = handleW, height = handleH)
                                    .clip(RoundedCornerShape(50))
                                    .background(activeColor)
                            )
                        }
                    } else {
                        val trackH = GlassSeekBar.TRACK_DP.dp
                        val thumbD = GlassSeekBar.THUMB_DP.dp
                        val grow by animateFloatAsState(
                            if (held) 1.15f else 1f,
                            if (held) spring(dampingRatio = 0.75f, stiffness = 600f)
                            else spring(dampingRatio = 0.32f, stiffness = 420f),
                            label = "seek-thumb"
                        )
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
                                        .graphicsLayer { scaleX = grow; scaleY = grow }
                                        .clip(CircleShape)
                                        .seaGlass(palette = thumbPalette)
                                )
                            }
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

    override fun onAttachedToWindow() {
        ensureOverlayOwners(this)
        super.onAttachedToWindow()
        glassView.visibility = VISIBLE
    }

    override fun onDetachedFromWindow() {
        glassView.visibility = GONE
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        measureChildWithMargins(bar, widthMeasureSpec, 0, heightMeasureSpec, 0)
        val w = resolveSize(bar.measuredWidth, widthMeasureSpec)
        val h = resolveSize(bar.measuredHeight, heightMeasureSpec)
        setMeasuredDimension(w, h)
        if (!isAttachedToWindow) return
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
        const val GLOW_DP = 7

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
            var up: android.view.ViewParent? = frame.parent
            var levels = 0
            while (up is ViewGroup && levels < 4) {
                up.clipChildren = false
                up.clipToPadding = false
                up = up.parent
                levels++
            }
        }
    }
}
