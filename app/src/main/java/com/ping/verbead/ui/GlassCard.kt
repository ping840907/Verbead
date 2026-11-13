package com.ping.verbead.ui

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.widget.NestedScrollView
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.ping.verbead.R
import com.ping.verbead.ui.style.ExpressivePalette
import com.ping.verbead.ui.style.UiStyle
import com.ping.verbead.ui.style.UiStyleManager
import com.ping.verbead.util.AppTheme

/**
 * Container card whose fill, corner geometry and border are driven by the active [UiStyleManager]
 * module ([com.ping.verbead.ui.style.ExpressiveStyleModule] or
 * [com.ping.verbead.ui.style.SeaGlassStyleModule]).
 */
class GlassCard @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    init {
        applyThemeTint()
    }

    /** Re-reads the theme colours and active UI style; call after a theme or style change. */
    fun applyThemeTint() {
        UiStyleManager.current(context).styleCard(this, UiStyleManager.isDark(context))
    }

    /** Kept for call-site compatibility; tonal/expressive cards need no backdrop binding. */
    fun bindBackdropTarget(@Suppress("UNUSED_PARAMETER") target: View?) = Unit
}

/** Screen backdrop drawn by the active [UiStyleManager] module. */
class GlassBackdropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    override fun onDraw(canvas: android.graphics.Canvas) {
        if (width <= 0 || height <= 0) return
        UiStyleManager.current(context)
            .drawBackdrop(this, canvas, width, height, UiStyleManager.isDark(context))
    }
}

object GlassStage {
    /**
     * Call right after setContentView. Puts a [GlassBackdropView] behind the screen content,
     * skins all UI components with the active style module, and automatically recreates back-stacked
     * activities if the user switches theme styles.
     */
    fun install(activity: Activity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        val root = content.getChildAt(0) ?: return
        content.removeView(root)

        val window = activity.window
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        val night = UiStyleManager.isDark(activity)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }

        val installedStyle = UiStyleManager.syncFromPrefs(activity)
        window.decorView.setTag(
            R.id.glass_installed_style,
            themeSignature(activity, installedStyle)
        )
        attachStyleSyncListener(activity)

        val backdrop = GlassBackdropView(activity)
        val stage = FrameLayout(activity)
        stage.addView(backdrop, FrameLayout.LayoutParams(-1, -1))
        root.setBackgroundColor(Color.TRANSPARENT)
        clearToolbars(root)
        val inset = FrameLayout(activity)
        inset.addView(root, FrameLayout.LayoutParams(-1, -1))
        stage.addView(inset, FrameLayout.LayoutParams(-1, -1))
        content.addView(stage, ViewGroup.LayoutParams(-1, -1))

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
        skin(root)
        UiStyleManager.current(activity).installEntranceAndPressMotion(root as? ViewGroup ?: stage)
    }

    private fun themeSignature(activity: Activity, style: UiStyle = UiStyleManager.syncFromPrefs(activity)): String =
        "${style.key}:${AppTheme.current(activity)}:${UiStyleManager.isDark(activity)}"

    private fun attachStyleSyncListener(activity: Activity) {
        val decor = activity.window.decorView
        val key = "glass_focus_listener_attached"
        if (decor.getTag(R.id.glass_tint) == key) return
        decor.setTag(R.id.glass_tint, key)
        decor.viewTreeObserver.addOnWindowFocusChangeListener(
            ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
                if (!hasFocus || activity.isFinishing || activity.isDestroyed) return@OnWindowFocusChangeListener
                val installed = decor.getTag(R.id.glass_installed_style) as? String ?: return@OnWindowFocusChangeListener
                val current = themeSignature(activity)
                if (installed != current) {
                    decor.post {
                        if (!activity.isFinishing && !activity.isDestroyed) {
                            activity.recreate()
                        }
                    }
                }
            }
        )
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

    /** Skins views that are inflated after the screen was set up (list rows, dialogs, overlays). */
    fun skin(root: View) {
        val buttons = ArrayList<GlassMaterialButton>()
        collectButtons(root, buttons)
        buttons.forEach { GlassButtonFrame.wrap(it) }
        val plates = ArrayList<View>()
        collectPlates(root, plates)
        plates.forEach { v ->
            (v as? GlassPlateHost)?.let { GlassPlateFrame.wrap(it, v) }
            (v as? GlassSeekBar)?.let { GlassSeekFrame.wrap(it) }
            if (v is GlassChipGroup || v is GlassChip) GlassChipFrame.wrap(v)
        }
        if (UiStyleManager.currentStyle(root.context) == UiStyle.EXPRESSIVE) {
            skinAuxiliaryDrawables(root)
        }
    }

    private fun skinAuxiliaryDrawables(root: View) {
        val ctx = root.context
        val dark = UiStyleManager.isDark(ctx)
        val scheme = ExpressivePalette.resolve(root, dark)
        val d = ctx.resources.displayMetrics.density

        val infoState = ContextCompat.getDrawable(ctx, R.drawable.bg_info_neutral)?.constantState
        val mockScreenState = ContextCompat.getDrawable(ctx, R.drawable.bg_mockup_screen)?.constantState
        val testInputState = ContextCompat.getDrawable(ctx, R.drawable.bg_test_input)?.constantState
        val shutterState = ContextCompat.getDrawable(ctx, R.drawable.bg_shutter_btn)?.constantState
        val capsuleIndState = ContextCompat.getDrawable(ctx, R.drawable.bg_capsule_indicator)?.constantState
        val pillBtnState = ContextCompat.getDrawable(ctx, R.drawable.bg_ocr_pill_btn)?.constantState

        fun walk(v: View) {
            val bgState = v.background?.constantState
            if (bgState != null) {
                when (bgState) {
                    infoState -> {
                        v.background = GradientDrawable().apply {
                            cornerRadius = 18f * d
                            setColor(ColorUtils.blendARGB(scheme.surfaceContainer, scheme.secondaryContainer, 0.55f))
                            setStroke((1f * d).toInt().coerceAtLeast(1), ColorUtils.setAlphaComponent(scheme.outlineVariant, 0x80))
                        }
                    }
                    mockScreenState -> {
                        v.background = GradientDrawable().apply {
                            cornerRadius = 26f * d
                            setColor(scheme.surfaceContainerLowest)
                            setStroke((1.5f * d).toInt().coerceAtLeast(1), ColorUtils.setAlphaComponent(scheme.outlineVariant, 0xA0))
                        }
                    }
                    testInputState -> {
                        v.background = GradientDrawable().apply {
                            cornerRadius = 14f * d
                            setColor(scheme.surface)
                            setStroke((1.5f * d).toInt().coerceAtLeast(1), ColorUtils.setAlphaComponent(scheme.outlineVariant, 0xAA))
                        }
                    }
                    shutterState -> {
                        v.background = GradientDrawable().apply {
                            shape = GradientDrawable.RECTANGLE
                            cornerRadius = 18f * d
                            setColor(0xFFFFFFFF.toInt())
                            setStroke((3f * d).toInt().coerceAtLeast(1), 0xFF9ECAFF.toInt())
                        }
                    }
                    capsuleIndState -> UiStyleManager.current(ctx).styleCapsuleIndicator(v)
                    pillBtnState -> {
                        v.background = GradientDrawable().apply {
                            cornerRadius = 14f * d
                            setColor(scheme.secondaryContainer)
                            setStroke((1f * d).toInt().coerceAtLeast(1), ColorUtils.setAlphaComponent(scheme.outlineVariant, 0x88))
                        }
                    }
                }
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
        }
        walk(root)
    }

    /** Gives a MaterialCardView the same surface style as [GlassCard]. */
    fun frost(card: MaterialCardView) {
        UiStyleManager.current(card.context).frostCard(card, UiStyleManager.isDark(card.context))
    }

    fun hairline(context: Context): Int {
        val v = View(context)
        return ColorUtils.setAlphaComponent(
            MaterialColors.getColor(v, com.google.android.material.R.attr.colorOnSurface, Color.BLACK), 0x1F
        )
    }

    private fun collectPlates(v: View, out: MutableList<View>) {
        if (v is GlassPlateHost || v is GlassSeekBar || v is GlassChipGroup || (v is GlassChip && v.parent !is GlassChipGroup)) out.add(v)
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
