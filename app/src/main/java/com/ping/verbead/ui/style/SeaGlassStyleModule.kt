package com.ping.verbead.ui.style

import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.ping.verbead.R
import com.ping.verbead.ui.GlassCard
import com.ping.verbead.ui.GlassMotion
import com.ping.verbead.ui.GlassStage

/**
 * SeaGlass style implementation: translucent tonal glass panes with top-light sheen,
 * radial gradient wallpaper backdrop, and calm motion physics.
 */
object SeaGlassStyleModule : UiStyleModule {
    override val style = UiStyle.SEA_GLASS
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun drawBackdrop(view: View, canvas: Canvas, width: Int, height: Int, dark: Boolean) {
        val w = width.toFloat()
        val h = height.toFloat()
        val bg = MaterialColors.getColor(view, com.google.android.material.R.attr.colorSurface, Color.WHITE)
        val a = MaterialColors.getColor(view, androidx.appcompat.R.attr.colorPrimary, Color.BLUE)
        val b = MaterialColors.getColor(view, com.google.android.material.R.attr.colorTertiary, a)
        canvas.drawColor(bg)
        blob(canvas, w * 0.1f, h * 0.1f, w * 0.9f, a, 0.40f)
        blob(canvas, w * 0.95f, h * 0.5f, w * 0.9f, b, 0.30f)
        blob(canvas, w * 0.1f, h * 0.95f, w * 0.8f, a, 0.28f)
    }

    private fun blob(c: Canvas, x: Float, y: Float, r: Float, color: Int, alpha: Float) {
        paint.shader = RadialGradient(
            x, y, r,
            ColorUtils.setAlphaComponent(color, (alpha * 255).toInt()),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        c.drawCircle(x, y, r, paint)
    }

    override fun styleCard(card: GlassCard, dark: Boolean) {
        val container = MaterialColors.getColor(
            card, com.google.android.material.R.attr.colorPrimaryContainer, Color.GRAY
        )
        val surface = MaterialColors.getColor(
            card, com.google.android.material.R.attr.colorSurface, Color.WHITE
        )
        val onSurface = MaterialColors.getColor(
            card, com.google.android.material.R.attr.colorOnSurface, Color.BLACK
        )
        val radius = 24f * card.resources.displayMetrics.density
        val stroke = (1f * card.resources.displayMetrics.density).toInt().coerceAtLeast(1)

        val fill = GradientDrawable().apply {
            cornerRadius = radius
            setColor(ColorUtils.setAlphaComponent(ColorUtils.blendARGB(surface, container, 0.35f), 0xD6))
            setStroke(stroke, ColorUtils.setAlphaComponent(onSurface, 0x1F))
        }
        val sheen = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(ColorUtils.setAlphaComponent(Color.WHITE, 0x1A), Color.TRANSPARENT)
        ).apply { cornerRadius = radius }
        card.background = LayerDrawable(arrayOf(fill, sheen))
        card.elevation = 0f
        card.clipToOutline = true
        card.outlineProvider = ViewOutlineProvider.BACKGROUND
    }

    override fun frostCard(card: MaterialCardView, dark: Boolean) {
        val ctx = card.context
        val container = MaterialColors.getColor(card, com.google.android.material.R.attr.colorPrimaryContainer, Color.GRAY)
        val surface = MaterialColors.getColor(card, com.google.android.material.R.attr.colorSurface, Color.WHITE)
        card.setCardBackgroundColor(
            ColorUtils.setAlphaComponent(ColorUtils.blendARGB(surface, container, 0.35f), 0xC4)
        )
        card.radius = 24f * ctx.resources.displayMetrics.density
        if (card.getTag(R.id.glass_tint) == null) {
            val sheen = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(ColorUtils.setAlphaComponent(Color.WHITE, 0x24), Color.TRANSPARENT)
            ).apply { cornerRadius = card.radius }
            card.overlay.add(sheen)
            card.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
                sheen.setBounds(0, 0, r - l, b - t)
            }
            card.setTag(R.id.glass_tint, sheen)
        }
        card.strokeColor = GlassStage.hairline(ctx)
        if (card.strokeWidth == 0) {
            card.strokeWidth = (1f * ctx.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        }
        card.cardElevation = 0f
    }

    override fun styleSelectableCard(card: MaterialCardView, isSelected: Boolean, dark: Boolean) {
        val density = card.resources.displayMetrics.density
        if (isSelected) {
            val primaryColor = ContextCompat.getColor(card.context, R.color.md_theme_light_primary)
            card.strokeColor = primaryColor
            card.strokeWidth = (1.5f * density + 0.5f).toInt()
        } else {
            card.strokeColor = GlassStage.hairline(card.context)
            card.strokeWidth = (1f * density + 0.5f).toInt()
        }
    }

    override fun applyChoiceButtonState(button: MaterialButton, isSelected: Boolean, dark: Boolean) {
        button.isSelected = isSelected
        val context = button.context
        // Theme colours, so the button stays readable in dark and dynamic (wallpaper) themes too.
        val primaryColor = MaterialColors.getColor(
            button, androidx.appcompat.R.attr.colorPrimary, ContextCompat.getColor(context, R.color.md_theme_light_primary)
        )
        val onPrimaryColor = MaterialColors.getColor(
            button, com.google.android.material.R.attr.colorOnPrimary, ContextCompat.getColor(context, R.color.md_theme_light_onPrimary)
        )
        val unselectedBg = MaterialColors.getColor(
            button, com.google.android.material.R.attr.colorSurfaceVariant, ContextCompat.getColor(context, R.color.surface_card)
        )
        val unselectedText = MaterialColors.getColor(
            button, com.google.android.material.R.attr.colorOnSurface, ContextCompat.getColor(context, R.color.text_primary)
        )
        val strokeColor = if (isSelected) primaryColor else ContextCompat.getColor(context, R.color.surface_card_stroke)
        val density = context.resources.displayMetrics.density
        val strokeWidthPx = (1.2f * density).toInt().coerceAtLeast(1)

        if (isSelected) {
            button.backgroundTintList = ColorStateList.valueOf(primaryColor)
            val selectedText =
                if (button.isEnabled || ColorUtils.calculateLuminance(onPrimaryColor) < 0.5) onPrimaryColor
                else unselectedText
            button.setTextColor(selectedText)
            button.iconTint = ColorStateList.valueOf(selectedText)
            button.strokeColor = ColorStateList.valueOf(primaryColor)
            button.strokeWidth = strokeWidthPx
            button.elevation = 2f * density
        } else {
            button.backgroundTintList = ColorStateList.valueOf(unselectedBg)
            button.setTextColor(unselectedText)
            button.iconTint = ColorStateList.valueOf(unselectedText)
            button.strokeColor = ColorStateList.valueOf(strokeColor)
            button.strokeWidth = strokeWidthPx
            button.elevation = 0f
        }
    }

    override fun styleIndicatorDot(dot: View, active: Boolean, dark: Boolean) {
        dot.setBackgroundResource(
            if (active) R.drawable.bg_indicator_dot_active else R.drawable.bg_indicator_dot_inactive
        )
    }

    override fun stylePhraseItemBackground(view: View, dragging: Boolean) {
        view.setBackgroundResource(
            if (dragging) R.drawable.bg_phrase_item_dragging else R.drawable.bg_phrase_item
        )
    }

    override fun styleDeleteZoneBackground(view: View, active: Boolean) {
        view.setBackgroundResource(
            if (active) R.drawable.bg_bubble_delete_zone_active else R.drawable.bg_bubble_delete_zone
        )
    }

    override fun styleCapsuleIndicator(view: View) {
        view.setBackgroundResource(R.drawable.bg_capsule_indicator)
    }

    override fun stylePulseViews(ring: View?, wave: View?) {
        ring?.setBackgroundResource(R.drawable.bg_bubble_pulse)
        wave?.setBackgroundResource(R.drawable.bg_bubble_pulse)
    }

    override fun installEntranceAndPressMotion(root: ViewGroup) {
        val density = root.resources.displayMetrics.density
        val cards = ArrayList<View>()
        collectGlassCards(root, cards)
        GlassMotion.staggerIn(cards.filter { it.visibility == View.VISIBLE }.take(8), rise = 26 * density)
        val rows = ArrayList<View>()
        GlassMotion.clickableRows(root, rows)
        rows.forEach { row ->
            var p: android.view.ViewParent? = row.parent
            while (p != null && p !is GlassCard) p = p.parent
            (p as? View)?.let { GlassMotion.press(row, it, 0.985f) }
        }
    }

    private fun collectGlassCards(v: View, out: MutableList<View>) {
        if (v is GlassCard) out.add(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) collectGlassCards(v.getChildAt(i), out)
    }
}
