package com.ping.verbead.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.ping.verbead.R
import com.ping.verbead.ui.style.ExpressivePalette
import com.ping.verbead.ui.style.UiStyle
import com.ping.verbead.ui.style.UiStyleManager
import dev.mttmcknn.seaglass.SeaGlassMaterialTheme
import dev.mttmcknn.seaglass.SeaGlassPalette
import dev.mttmcknn.seaglass.SeaGlassShape
import dev.mttmcknn.seaglass.rememberSeaGlassColorScheme
import dev.mttmcknn.seaglass.seaGlass

private val PanelRecipe = SmallGlassRecipe.copy(thickness = 3.dp, frost = 0.3f, bulge = 0.2f)

/** Overlay windows have no Activity; give the window root the owners Compose looks for. */
internal fun ensureOverlayOwners(view: View) {
    val root = view.rootView
    if (root.getTag(androidx.lifecycle.runtime.R.id.view_tree_lifecycle_owner) == null) {
        root.setViewTreeLifecycleOwner(OverlayOwner)
    }
    if (root.getTag(androidx.savedstate.R.id.view_tree_saved_state_registry_owner) == null) {
        root.setViewTreeSavedStateRegistryOwner(OverlayOwner)
    }
}

private fun panelSurface(context: Context, radiusDp: Float): ComposeView = ComposeView(context).apply {
    setContent {
        val style = UiStyleManager.currentStyle(context)
        val dark = isSystemInDarkTheme()
        val colors = rememberSeaGlassColorScheme(
            dynamicColor = true,
            darkTheme = if (style == UiStyle.EXPRESSIVE) dark else true
        )
        MaterialTheme(colorScheme = colors) {
            if (style == UiStyle.EXPRESSIVE) {
                val cs = MaterialTheme.colorScheme
                val corner = (radiusDp * 0.58f).coerceIn(12f, 16f).dp
                val outerShape = RoundedCornerShape(corner)
                val startColor = if (dark) lerp(cs.primaryContainer, cs.primary, 0.35f) else lerp(cs.primary, cs.primaryContainer, 0.18f)
                val endColor = if (dark) lerp(cs.primaryContainer, Color.Black, 0.22f) else lerp(cs.primary, Color.Black, 0.16f)
                val outerRim = if (dark) cs.primary.copy(alpha = 0.55f) else cs.primaryContainer.copy(alpha = 0.72f)
                Box(
                    Modifier
                        .fillMaxSize()
                        .clip(outerShape)
                        .background(
                            Brush.verticalGradient(
                                listOf(startColor.copy(alpha = 0.96f), endColor.copy(alpha = 0.96f))
                            )
                        )
                        .border(1.5.dp, outerRim, outerShape)
                )
            } else {
                val shape = RoundedCornerShape(radiusDp.dp)
                val cs = MaterialTheme.colorScheme
                val body = lerp(cs.primaryContainer, Color.Black, 0.25f)
                val palette = SeaGlassPalette(body, lerp(body, Color.White, .3f), Color.White)
                SeaGlassMaterialTheme(style = PanelRecipe, shape = SeaGlassShape.Capsule) {
                    Box(Modifier.fillMaxSize().clip(shape).seaGlass(palette = palette))
                }
            }
        }
    }
}

private fun radiusOf(context: Context, attrs: AttributeSet?, fallbackDp: Float): Float {
    var r = fallbackDp
    if (attrs != null) {
        val a = context.obtainStyledAttributes(attrs, R.styleable.GlassPanel)
        r = a.getDimension(R.styleable.GlassPanel_glassRadius, fallbackDp * context.resources.displayMetrics.density) /
            context.resources.displayMetrics.density
        a.recycle()
    }
    return r
}

private fun frostedOf(context: Context, attrs: AttributeSet?): Boolean {
    if (attrs == null) return false
    val a = context.obtainStyledAttributes(attrs, R.styleable.GlassPanel)
    val f = a.getBoolean(R.styleable.GlassPanel_glassFrosted, false)
    a.recycle()
    return f
}

private fun frostedDrawable(context: Context, radiusDp: Float): Drawable {
    val d = context.resources.displayMetrics.density
    if (UiStyleManager.currentStyle(context) == UiStyle.EXPRESSIVE) {
        val ov = ExpressivePalette.resolveOverlay(context)
        val fill = GradientDrawable().apply {
            cornerRadius = (radiusDp + 4f) * d
            setColor(ov.panelSurface)
            setStroke((1.5f * d).toInt().coerceAtLeast(1), ov.panelStroke)
        }
        val glow = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(ov.panelHeaderTint, 0x00000000)
        ).apply { cornerRadius = (radiusDp + 4f) * d }
        return LayerDrawable(arrayOf(fill, glow))
    }

    val tint = if (android.os.Build.VERSION.SDK_INT >= 31) {
        runCatching { context.getColor(android.R.color.system_accent1_900) }.getOrDefault(0xFF1E232A.toInt())
    } else 0xFF1E232A.toInt()
    val fill = GradientDrawable().apply {
        cornerRadius = radiusDp * d
        setColor(ColorUtils.setAlphaComponent(tint, 0xF4))
        setStroke((1f * d).toInt().coerceAtLeast(1), 0x33FFFFFF)
    }
    val sheen = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(0x1FFFFFFF, 0x00FFFFFF)
    ).apply { cornerRadius = radiusDp * d }
    return LayerDrawable(arrayOf(fill, sheen))
}

/**
 * FrameLayout with a themed floating panel behind its children.
 */
class GlassFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val radiusDp = radiusOf(context, attrs, 18f)
    private val frosted = frostedOf(context, attrs)
    private val glass: ComposeView = panelSurface(context, radiusDp)

    init {
        clipChildren = false
        clipToPadding = false
        if (frosted) background = frostedDrawable(context, radiusDp)
        glass.translationZ = -1f
        glass.visibility = View.GONE
        if (!frosted) super.addView(glass, 0, LayoutParams(0, 0))
    }

    fun refreshPanelStyle() {
        if (frosted) background = frostedDrawable(context, radiusDp)
        invalidate()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (frosted || !isAttachedToWindow) return
        glass.measure(
            MeasureSpec.makeMeasureSpec(right - left, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(bottom - top, MeasureSpec.EXACTLY)
        )
        glass.layout(0, 0, right - left, bottom - top)
    }

    override fun onAttachedToWindow() {
        ensureOverlayOwners(this)
        super.onAttachedToWindow()
        if (frosted) refreshPanelStyle() else glass.visibility = View.VISIBLE
    }

    override fun onDetachedFromWindow() {
        if (!frosted) glass.visibility = View.GONE
        super.onDetachedFromWindow()
    }

    override fun removeAllViews() {
        val keep = if (frosted) 0 else 1
        if (childCount > keep) removeViews(keep, childCount - keep)
    }
}

/**
 * LinearLayout with a themed floating panel behind its children.
 */
class GlassLinearLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val radiusDp = radiusOf(context, attrs, 20f)
    private val frosted = frostedOf(context, attrs)
    private val glass: ComposeView = panelSurface(context, radiusDp)

    init {
        clipChildren = false
        clipToPadding = false
        if (frosted) background = frostedDrawable(context, radiusDp)
        glass.translationZ = -1f
        glass.visibility = View.GONE
        if (!frosted) super.addView(glass, 0, LayoutParams(0, 0))
    }

    fun refreshPanelStyle() {
        if (frosted) background = frostedDrawable(context, radiusDp)
        invalidate()
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        if (frosted || !isAttachedToWindow) return
        glass.measure(
            MeasureSpec.makeMeasureSpec(r - l, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(b - t, MeasureSpec.EXACTLY)
        )
        glass.layout(0, 0, r - l, b - t)
    }

    override fun onAttachedToWindow() {
        ensureOverlayOwners(this)
        super.onAttachedToWindow()
        if (frosted) refreshPanelStyle() else glass.visibility = View.VISIBLE
    }

    override fun onDetachedFromWindow() {
        if (!frosted) glass.visibility = View.GONE
        super.onDetachedFromWindow()
    }

    override fun removeAllViews() {
        val keep = if (frosted) 0 else 1
        if (childCount > keep) removeViews(keep, childCount - keep)
    }
}
