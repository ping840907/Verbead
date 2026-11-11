package com.ping.verbead.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.widget.NestedScrollView
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.MaterialColors

/**
 * Frosted tonal card. Fill and edge colours come from the current Material theme, so they follow
 * the wallpaper (Material You). Deliberately quiet: a translucent tonal fill, a faint top-light
 * sheen and a hairline edge, so the soft backdrop shows through without bevels or heavy rims.
 */
class GlassCard @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    init {
        applyThemeTint()
    }

    /** Re-reads the theme colours; call after a theme or night-mode change. */
    fun applyThemeTint() {
        val container = MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorPrimaryContainer, Color.GRAY
        )
        val surface = MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorSurface, Color.WHITE
        )
        val onSurface = MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorOnSurface, Color.BLACK
        )
        val radius = 24f * resources.displayMetrics.density
        val stroke = (1f * resources.displayMetrics.density).toInt().coerceAtLeast(1)

        val fill = GradientDrawable().apply {
            cornerRadius = radius
            setColor(ColorUtils.setAlphaComponent(ColorUtils.blendARGB(surface, container, 0.35f), 0xD6))
            setStroke(stroke, ColorUtils.setAlphaComponent(onSurface, 0x1F))
        }
        val sheen = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(ColorUtils.setAlphaComponent(Color.WHITE, 0x1A), Color.TRANSPARENT)
        ).apply { cornerRadius = radius }
        background = LayerDrawable(arrayOf(fill, sheen))
        clipToOutline = true
        outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
    }

    /** Kept for call-site compatibility; the tonal card needs no backdrop binding. */
    fun bindBackdropTarget(@Suppress("UNUSED_PARAMETER") target: View?) = Unit
}

/** Soft wallpaper-accent gradient that the glass cards refract and blur. */
class GlassBackdropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: android.graphics.Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val bg = themeColor(com.google.android.material.R.attr.colorSurface, Color.WHITE)
        val a = themeColor(androidx.appcompat.R.attr.colorPrimary, Color.BLUE)
        val b = themeColor(com.google.android.material.R.attr.colorTertiary, a)
        canvas.drawColor(bg)
        blob(canvas, w * 0.1f, h * 0.1f, w * 0.9f, a, 0.40f)
        blob(canvas, w * 0.95f, h * 0.5f, w * 0.9f, b, 0.30f)
        blob(canvas, w * 0.1f, h * 0.95f, w * 0.8f, a, 0.28f)
    }

    private fun blob(c: android.graphics.Canvas, x: Float, y: Float, r: Float, color: Int, alpha: Float) {
        paint.shader = android.graphics.RadialGradient(
            x, y, r,
            ColorUtils.setAlphaComponent(color, (alpha * 255).toInt()),
            Color.TRANSPARENT,
            android.graphics.Shader.TileMode.CLAMP
        )
        c.drawCircle(x, y, r, paint)
    }

    private fun themeColor(attr: Int, fallback: Int) = MaterialColors.getColor(this, attr, fallback)
}

object GlassStage {
    /**
     * Call right after setContentView. Puts a [GlassBackdropView] behind the screen content and
     * binds every [GlassCard] to it. The content's own opaque background is cleared so the
     * backdrop shows through.
     */
    fun install(activity: android.app.Activity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        val root = content.getChildAt(0) ?: return
        content.removeView(root)

        // Edge to edge: the backdrop fills the whole window, behind the status bar, the toolbar
        // and the navigation bar. System bars become transparent and the content is inset.
        val window = activity.window
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        val night = (activity.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }

        val backdrop = GlassBackdropView(activity)
        val stage = FrameLayout(activity)
        stage.addView(backdrop, FrameLayout.LayoutParams(-1, -1))
        root.setBackgroundColor(Color.TRANSPARENT)
        clearToolbars(root)
        val inset = FrameLayout(activity)
        inset.addView(root, FrameLayout.LayoutParams(-1, -1))
        stage.addView(inset, FrameLayout.LayoutParams(-1, -1))
        content.addView(stage, ViewGroup.LayoutParams(-1, -1))

        // Top and sides are padded by the window; the bottom inset goes onto the scrolling
        // list so its content can still scroll underneath the transparent navigation bar.
        val scroller = findScroller(root)
        val scrollBottom = scroller?.paddingBottom ?: 0
        scroller?.clipToPadding = false
        ViewCompat.setOnApplyWindowInsetsListener(stage) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            inset.setPadding(bars.left, bars.top, bars.right, if (scroller == null) bars.bottom else 0)
            scroller?.setPadding(
                scroller.paddingLeft, scroller.paddingTop, scroller.paddingRight,
                scrollBottom + bars.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(stage)

        bind(root, backdrop)
        val buttons = ArrayList<GlassMaterialButton>()
        collectButtons(root, buttons)
        buttons.forEach { GlassButtonFrame.wrap(it) }
        val plates = ArrayList<View>()
        collectPlates(root, plates)
        plates.forEach { v -> (v as? GlassPlateHost)?.let { GlassPlateFrame.wrap(it, v) } }
    }

    /** Toolbars and app bars paint their own opaque colour; let the backdrop show instead. */
    private fun clearToolbars(v: View) {
        if (v is MaterialToolbar) v.setBackgroundColor(Color.TRANSPARENT)
        if (v is AppBarLayout) {
            v.setBackgroundColor(Color.TRANSPARENT)
            v.isLiftOnScroll = false
            v.statusBarForeground = null
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) clearToolbars(v.getChildAt(i))
    }

    private fun findScroller(v: View): NestedScrollView? {
        if (v is NestedScrollView) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findScroller(v.getChildAt(i))?.let { return it }
        return null
    }


    /** Skins views that are inflated after the screen was set up (list rows, dialogs). */
    fun skin(root: View) {
        val buttons = ArrayList<GlassMaterialButton>()
        collectButtons(root, buttons)
        buttons.forEach { GlassButtonFrame.wrap(it) }
        val plates = ArrayList<View>()
        collectPlates(root, plates)
        plates.forEach { v -> (v as? GlassPlateHost)?.let { GlassPlateFrame.wrap(it, v) } }
    }

    /** Gives a MaterialCardView the same frosted look as [GlassCard]. */
    fun frost(card: com.google.android.material.card.MaterialCardView) {
        val ctx = card.context
        val container = MaterialColors.getColor(card, com.google.android.material.R.attr.colorPrimaryContainer, Color.GRAY)
        val surface = MaterialColors.getColor(card, com.google.android.material.R.attr.colorSurface, Color.WHITE)
        card.setCardBackgroundColor(
            ColorUtils.setAlphaComponent(ColorUtils.blendARGB(surface, container, 0.35f), 0xD6)
        )
        card.strokeColor = hairline(ctx)
        if (card.strokeWidth == 0) card.strokeWidth = (1f * ctx.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        card.cardElevation = 0f
    }

    fun hairline(context: Context): Int {
        val v = View(context)
        return ColorUtils.setAlphaComponent(
            MaterialColors.getColor(v, com.google.android.material.R.attr.colorOnSurface, Color.BLACK), 0x1F
        )
    }

    private fun collectPlates(v: View, out: MutableList<View>) {
        if (v is GlassPlateHost) out.add(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) collectPlates(v.getChildAt(i), out)
    }

    private fun collectButtons(v: View, out: MutableList<GlassMaterialButton>) {
        if (v is GlassMaterialButton) out.add(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) collectButtons(v.getChildAt(i), out)
    }

    private fun bind(v: View, backdrop: View) {
        if (v is GlassCard) v.bindBackdropTarget(backdrop)
        if (v is ViewGroup) for (i in 0 until v.childCount) bind(v.getChildAt(i), backdrop)
    }
}
