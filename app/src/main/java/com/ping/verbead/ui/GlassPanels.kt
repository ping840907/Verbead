package com.ping.verbead.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.ping.verbead.R
import dev.mttmcknn.seaglass.SeaGlassMaterialTheme
import dev.mttmcknn.seaglass.SeaGlassPalette
import dev.mttmcknn.seaglass.SeaGlassShape
import dev.mttmcknn.seaglass.rememberSeaGlassColorScheme
import dev.mttmcknn.seaglass.seaGlass

/**
 * Sea Glass backgrounds for the floating overlay windows (mode menu, quick phrases, OCR and
 * scanner windows, the preview pill). These float over other apps and carry white text, so the
 * glass is always a dark body tinted by the wallpaper colours (Material You).
 */
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

private fun panelGlass(context: Context, radiusDp: Float): ComposeView = ComposeView(context).apply {
    setContent {
        // Always the dark scheme: these panels carry white icons and text over other apps.
        val colors = rememberSeaGlassColorScheme(dynamicColor = true, darkTheme = true)
        MaterialTheme(colorScheme = colors) {
            val cs = MaterialTheme.colorScheme
            // Dark glass keeps the white icons and text readable over any app.
            val body = lerp(cs.primaryContainer, Color.Black, 0.25f)
            val palette = SeaGlassPalette(body, lerp(body, Color.White, .3f), Color.White)
            val shape = RoundedCornerShape(radiusDp.dp)
            SeaGlassMaterialTheme(style = PanelRecipe, shape = SeaGlassShape.Capsule) {
                // Clipped to the panel's own outline: the cast shadow would be cut flat in a small window.
                Box(Modifier.fillMaxSize().clip(shape).seaGlass(palette = palette))
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

/** Dark, translucent, wallpaper-tinted fill with a faint top light and a hairline edge. */
private fun frostedDrawable(context: Context, radiusDp: Float): android.graphics.drawable.Drawable {
    val d = context.resources.displayMetrics.density
    val tint = if (android.os.Build.VERSION.SDK_INT >= 31) {
        runCatching { context.getColor(android.R.color.system_accent1_900) }.getOrDefault(0xFF1E232A.toInt())
    } else 0xFF1E232A.toInt()
    val fill = android.graphics.drawable.GradientDrawable().apply {
        cornerRadius = radiusDp * d
        setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(tint, 0xF4))
        setStroke((1f * d).toInt().coerceAtLeast(1), 0x33FFFFFF)
    }
    val sheen = android.graphics.drawable.GradientDrawable(
        android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(0x1FFFFFFF, 0x00FFFFFF)
    ).apply { cornerRadius = radiusDp * d }
    return android.graphics.drawable.LayerDrawable(arrayOf(fill, sheen))
}

/**
 * FrameLayout with a Sea Glass panel behind its children. The glass is child 0 with zero size, so
 * it never decides how big a wrap_content panel is; after the content is laid out it is stretched
 * over the whole panel.
 */
class GlassFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val frosted = frostedOf(context, attrs)
    private val glass: ComposeView = panelGlass(context, radiusOf(context, attrs, 18f))

    init {
        clipChildren = false
        clipToPadding = false
        if (frosted) background = frostedDrawable(context, radiusOf(context, attrs, 18f))
        glass.translationZ = -1f
        // Compose needs a window; until the panel is attached (it may be measured off-screen first) the glass stays out of layout.
        glass.visibility = View.GONE
        if (!frosted) super.addView(glass, 0, LayoutParams(0, 0))
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
        if (!frosted) glass.visibility = View.VISIBLE
    }

    override fun onDetachedFromWindow() {
        if (!frosted) glass.visibility = View.GONE
        super.onDetachedFromWindow()
    }

    override fun removeAllViews() {
        // Keep the glass layer (child 0) when the content is rebuilt.
        val keep = if (frosted) 0 else 1
        if (childCount > keep) removeViews(keep, childCount - keep)
    }
}

/**
 * LinearLayout with a Sea Glass panel behind its children. The glass is child 0 with zero size so
 * it takes no room in the linear layout, and is then stretched over the whole panel.
 */
class GlassLinearLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val frosted = frostedOf(context, attrs)
    private val glass: ComposeView = panelGlass(context, radiusOf(context, attrs, 20f))

    init {
        clipChildren = false
        clipToPadding = false
        if (frosted) background = frostedDrawable(context, radiusOf(context, attrs, 20f))
        // Behind every sibling, whatever order the content is added in later.
        glass.translationZ = -1f
        // Compose needs a window; until the panel is attached (it may be measured off-screen first) the glass stays out of layout.
        glass.visibility = View.GONE
        if (!frosted) super.addView(glass, 0, LayoutParams(0, 0))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        // The linear layout lays the content out while the glass is still 0 x 0; only afterwards is
        // the glass measured and stretched over the whole panel (measuring it earlier would push the
        // content down by the panel's height).
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
        if (!frosted) glass.visibility = View.VISIBLE
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
