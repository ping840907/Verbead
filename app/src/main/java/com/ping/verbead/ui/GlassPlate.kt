package com.ping.verbead.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import com.google.android.material.color.MaterialColors
import com.ping.verbead.R
import com.ping.verbead.ui.style.UiStyle
import com.ping.verbead.ui.style.UiStyleManager
import dev.mttmcknn.seaglass.SeaGlassMaterialTheme
import dev.mttmcknn.seaglass.SeaGlassPalette
import dev.mttmcknn.seaglass.SeaGlassShape
import dev.mttmcknn.seaglass.rememberSeaGlassColorScheme
import dev.mttmcknn.seaglass.seaGlass

/** Views that carry a fixed glass/expressive colour for overlay windows. */
internal interface OverlayTinted {
    val overlayTint: Int?
    val followBubble: Boolean
    val accent: Boolean get() = false
}

/** A view whose own background is replaced by a themed plate once GlassStage wraps it. */
internal interface GlassPlateHost {
    var plate: GlassPlateFrame?
    val plateShape: SeaGlassShape

    /** Pigment used when the view never had a coloured background of its own. */
    fun defaultPlateTint(): Int
}

/**
 * Themed plate behind a single child view (icon container, status tag).
 * Renders either Material 3 Expressive squircle/pill containers or SeaGlass discs/capsules.
 */
class GlassPlateFrame internal constructor(
    context: Context,
    private val child: View,
    private val shape: SeaGlassShape
) : FrameLayout(context) {

    private val tintState = mutableIntStateOf(Color.TRANSPARENT)
    private val pressedState = mutableStateOf(false)
    internal var darkOverlay = false
    private val rebuild = mutableIntStateOf(0)
    internal var followBubble = false
    internal var accent = false
    private val glow = (GLOW_DP * resources.displayMetrics.density).toInt()
    private val glassView: ComposeView

    init {
        clipChildren = false
        clipToPadding = false
        glassView = ComposeView(context).apply {
            visibility = GONE
            setContent {
                val style = UiStyleManager.currentStyle(context)
                val dark = isSystemInDarkTheme()
                val colors = rememberSeaGlassColorScheme(
                    dynamicColor = true,
                    darkTheme = if (followBubble) dark else dark || darkOverlay
                )
                MaterialTheme(colorScheme = colors) {
                    val tint by tintState
                    val pressed by pressedState
                    val generation by rebuild

                    if (style == UiStyle.EXPRESSIVE) {
                        val cs = MaterialTheme.colorScheme
                        androidx.compose.runtime.key(generation) {
                            if (shape == SeaGlassShape.Disc) {
                                val cornerPct by animateIntAsState(
                                    targetValue = if (pressed) 22 else 34,
                                    animationSpec = spring(dampingRatio = 0.6f, stiffness = 550f),
                                    label = "m3e-plate-disc"
                                )
                                val containerShape = RoundedCornerShape(percent = cornerPct)
                                val fill = when {
                                    followBubble && accent -> lerp(cs.primary, ComposeColor.Black, if (dark) 0.28f else 0.14f)
                                    followBubble -> if (dark) lerp(cs.primaryContainer, cs.surface, 0.32f) else lerp(cs.primaryContainer, cs.primary, 0.28f)
                                    else -> {
                                        val base = ComposeColor(tint or (0xFF shl 24))
                                        if (dark) lerp(base, cs.primaryContainer, 0.25f) else base
                                    }
                                }
                                if (followBubble) SideEffect { applyContentTint(fill) }
                                val stroke = when {
                                    followBubble && accent -> cs.primary.copy(alpha = 0.62f)
                                    followBubble -> cs.primary.copy(alpha = 0.38f)
                                    else -> cs.primary.copy(alpha = 0.22f)
                                }
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .padding(GLOW_DP.dp)
                                        .clip(containerShape)
                                        .background(fill)
                                        .border(1.dp, stroke, containerShape)
                                )
                            } else {
                                val cornerDp = if (pressed) 8.dp else 11.dp
                                val pillShape = RoundedCornerShape(cornerDp)
                                val fill = when {
                                    followBubble && accent -> lerp(cs.primary, ComposeColor.Black, 0.25f)
                                    followBubble -> lerp(cs.surface, cs.primaryContainer, 0.38f).copy(alpha = 0.90f)
                                    else -> ComposeColor(tint or (0xFF shl 24))
                                }
                                if (followBubble) SideEffect { applyContentTint(fill) }
                                val stroke = when {
                                    followBubble && accent -> cs.primary.copy(alpha = 0.58f)
                                    followBubble -> cs.primary.copy(alpha = 0.35f)
                                    else -> cs.outlineVariant.copy(alpha = 0.55f)
                                }
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .padding(GLOW_DP.dp)
                                        .clip(pillShape)
                                        .background(fill)
                                        .border(1.dp, stroke, pillShape)
                                )
                            }
                        }
                    } else {
                        val body = if (followBubble) {
                            val base = if (dark) colors.primaryContainer else colors.primary
                            if (accent) lerp(colors.primary, ComposeColor.Black, 0.38f) else base
                        } else {
                            lerp(ComposeColor(tint or (0xFF shl 24)), ComposeColor.White, 0.06f)
                        }
                        if (followBubble) SideEffect { applyContentTint(body) }
                        val palette = SeaGlassPalette(
                            body, lerp(body, ComposeColor.White, .35f),
                            if (followBubble) ComposeColor.White else ComposeColor.Black
                        )
                        androidx.compose.runtime.key(generation) {
                            SeaGlassMaterialTheme(style = if (followBubble) BubbleRecipe else SmallGlassRecipe, shape = shape) {
                                val clipShape = if (shape == SeaGlassShape.Disc) CircleShape else RoundedCornerShape(50)
                                Box(Modifier.fillMaxSize().padding(GLOW_DP.dp).clip(clipShape).seaGlass(palette = palette))
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

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration?) {
        super.onConfigurationChanged(newConfig)
        rebuild.intValue++
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
        measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, 0)
        val w = resolveSize(child.measuredWidth, widthMeasureSpec)
        val h = resolveSize(child.measuredHeight, heightMeasureSpec)
        setMeasuredDimension(w, h)
        if (!isAttachedToWindow) return
        glassView.measure(
            MeasureSpec.makeMeasureSpec(w + 2 * glow, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(h + 2 * glow, MeasureSpec.EXACTLY)
        )
    }

    /** Content on a plate that follows the bubble: dark on a light body, white on a dark one. */
    private fun applyContentTint(body: ComposeColor) {
        val tint = panelContentTint(body)
        when (child) {
            is android.widget.ImageView -> child.imageTintList = tint
            is android.widget.TextView -> child.setTextColor(tint.defaultColor)
        }
    }

    internal fun setTint(argb: Int) {
        tintState.intValue = argb
    }

    internal fun onPressedChanged(pressed: Boolean) {
        pressedState.value = pressed
    }

    internal fun rebuildPlate() {
        rebuild.intValue++
    }

    internal companion object {
        const val GLOW_DP = 2

        fun wrap(host: GlassPlateHost, view: View) {
            val parent = view.parent as? ViewGroup ?: return
            if (host.plate != null) return
            val index = parent.indexOfChild(view)
            val lp = view.layoutParams
            val frame = GlassPlateFrame(view.context, view, host.plateShape)
            parent.removeViewAt(index)
            frame.visibility = view.visibility
            parent.addView(frame, index, lp)
            val wrapW = lp.width == ViewGroup.LayoutParams.WRAP_CONTENT
            val wrapH = lp.height == ViewGroup.LayoutParams.WRAP_CONTENT
            frame.addView(
                view,
                LayoutParams(
                    if (wrapW) LayoutParams.WRAP_CONTENT else LayoutParams.MATCH_PARENT,
                    if (wrapH) LayoutParams.WRAP_CONTENT else LayoutParams.MATCH_PARENT
                )
            )
            host.plate = frame
            if (view.isClickable) GlassMotion.press(view, frame, 0.9f)
            frame.darkOverlay = (host as? OverlayTinted)?.overlayTint != null
            frame.followBubble = (host as? OverlayTinted)?.followBubble == true
            frame.accent = (host as? OverlayTinted)?.accent == true
            frame.setTint(host.defaultPlateTint())
            view.background = null
        }
    }
}

private fun solidColorOf(d: Drawable?): Int? =
    (d as? GradientDrawable)?.color?.defaultColor

/** Icon holder drawn as an expressive squircle or SeaGlass disc. */
class GlassIconView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr), GlassPlateHost, OverlayTinted {

    override var plate: GlassPlateFrame? = null
    override val plateShape get() = SeaGlassShape.Disc

    override val overlayTint: Int? = readOverlayTint(context, attrs)
    override val followBubble: Boolean = readFollowBubble(context, attrs)

    override fun defaultPlateTint(): Int = overlayTint ?: MaterialColors.getColor(
        this, com.google.android.material.R.attr.colorPrimaryContainer, Color.GRAY
    )

    override fun setBackground(background: Drawable?) {
        val p = plate
        if (p == null) super.setBackground(background)
        else {
            p.setTint(solidColorOf(background) ?: defaultPlateTint())
            super.setBackground(null)
        }
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        plate?.onPressedChanged(pressed)
    }

    override fun setVisibility(visibility: Int) {
        super.setVisibility(visibility)
        (parent as? GlassPlateFrame)?.visibility = visibility
    }
}

/**
 * Status or count tag drawn as an expressive pill/squircle badge or SeaGlass capsule.
 */
class GlassTagText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr), GlassPlateHost, OverlayTinted {

    override var plate: GlassPlateFrame? = null
    override val plateShape get() = SeaGlassShape.Capsule

    override val overlayTint: Int? = readOverlayTint(context, attrs)
    override val followBubble: Boolean = readFollowBubble(context, attrs)
    override val accent: Boolean = readAccent(context, attrs)

    private var xmlTint: Int? = null

    override fun defaultPlateTint(): Int = overlayTint ?: pigment(
        xmlTint ?: MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorSecondaryContainer, Color.GRAY
        )
    )

    private fun pigment(container: Int): Int = container

    override fun setBackground(background: Drawable?) {
        val c = solidColorOf(background)
        val p = plate
        if (p == null) {
            if (c != null) xmlTint = c
            super.setBackground(background)
        } else {
            p.setTint(if (c != null) pigment(c) else defaultPlateTint())
            super.setBackground(null)
        }
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        plate?.onPressedChanged(pressed)
    }

    override fun setVisibility(visibility: Int) {
        super.setVisibility(visibility)
        (parent as? GlassPlateFrame)?.visibility = visibility
    }
}

private fun readOverlayTint(context: Context, attrs: AttributeSet?): Int? {
    if (attrs == null) return null
    val a = context.obtainStyledAttributes(attrs, R.styleable.GlassOverlay)
    val c = if (a.hasValue(R.styleable.GlassOverlay_glassTint)) a.getColor(R.styleable.GlassOverlay_glassTint, 0) else null
    a.recycle()
    return c
}

private fun readFollowBubble(context: Context, attrs: AttributeSet?): Boolean {
    if (attrs == null) return false
    val a = context.obtainStyledAttributes(attrs, R.styleable.GlassOverlay)
    val v = a.getBoolean(R.styleable.GlassOverlay_glassFollowBubble, false)
    a.recycle()
    return v
}

private fun readAccent(context: Context, attrs: AttributeSet?): Boolean {
    if (attrs == null) return false
    val a = context.obtainStyledAttributes(attrs, R.styleable.GlassOverlay)
    val v = a.getBoolean(R.styleable.GlassOverlay_glassAccent, false)
    a.recycle()
    return v
}
