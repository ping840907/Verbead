package com.ping.verbead.ui.style

import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.OvershootInterpolator
import androidx.core.graphics.ColorUtils
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.ping.verbead.R
import com.ping.verbead.ui.GlassCard
import com.ping.verbead.ui.GlassMotion

/**
 * Material 3 Expressive style module:
 * - High-contrast tonal surface hierarchy (`surfaceContainerLow` / `primaryContainer` hero cards)
 * - Soft organic geometric backdrop contours
 * - Squircle corner geometry (28dp outer cards, 22dp inner rows, morphing buttons/chips/switches)
 * - Spring-like overshoot entrance and tactile press physics
 */
object ExpressiveStyleModule : UiStyleModule {
    override val style = UiStyle.EXPRESSIVE

    private val shapePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tempRect = RectF()

    private val heroCardIds = setOf(
        R.id.card_status_indicator,
        R.id.card_asr_combo
    )

    override fun drawBackdrop(view: View, canvas: Canvas, width: Int, height: Int, dark: Boolean) {
        val scheme = ExpressivePalette.resolve(view, dark)
        val w = width.toFloat()
        val h = height.toFloat()

        canvas.drawColor(scheme.surface)

        // Subtle tilted squircle pill top-right in primaryContainer tone
        canvas.save()
        canvas.rotate(-14f, w * 0.85f, h * 0.12f)
        shapePaint.color = ColorUtils.setAlphaComponent(scheme.primaryContainer, if (dark) 0x38 else 0x55)
        tempRect.set(w * 0.42f, -h * 0.08f, w * 1.18f, h * 0.30f)
        canvas.drawRoundRect(tempRect, w * 0.22f, w * 0.22f, shapePaint)
        canvas.restore()

        // Organic secondaryContainer pill center-left
        canvas.save()
        canvas.rotate(18f, w * 0.15f, h * 0.52f)
        shapePaint.color = ColorUtils.setAlphaComponent(scheme.tertiaryContainer, if (dark) 0x2E else 0x48)
        tempRect.set(-w * 0.25f, h * 0.36f, w * 0.55f, h * 0.68f)
        canvas.drawRoundRect(tempRect, w * 0.26f, w * 0.26f, shapePaint)
        canvas.restore()

        // Warm bottom-right primary/secondary accent wave
        canvas.save()
        canvas.rotate(-8f, w * 0.78f, h * 0.90f)
        shapePaint.color = ColorUtils.setAlphaComponent(scheme.secondaryContainer, if (dark) 0x30 else 0x4D)
        tempRect.set(w * 0.32f, h * 0.74f, w * 1.12f, h * 1.06f)
        canvas.drawRoundRect(tempRect, w * 0.24f, w * 0.24f, shapePaint)
        canvas.restore()
    }

    override fun styleCard(card: GlassCard, dark: Boolean) {
        val scheme = ExpressivePalette.resolve(card, dark)
        val d = card.resources.displayMetrics.density
        val isHero = card.id in heroCardIds
        val radius = 28f * d

        val fillColor = if (isHero) {
            ColorUtils.blendARGB(scheme.surfaceContainerLow, scheme.primaryContainer, if (dark) 0.42f else 0.52f)
        } else {
            scheme.surfaceContainerLow
        }
        val strokeColor = if (isHero) {
            ColorUtils.setAlphaComponent(scheme.primary, if (dark) 0x59 else 0x47)
        } else {
            ColorUtils.setAlphaComponent(scheme.outlineVariant, if (dark) 0x70 else 0x99)
        }
        val strokeWidth = ((if (isHero) 1.5f else 1f) * d).toInt().coerceAtLeast(1)

        card.background = GradientDrawable().apply {
            cornerRadius = radius
            setColor(fillColor)
            setStroke(strokeWidth, strokeColor)
        }
        card.elevation = if (isHero) 3f * d else 1.5f * d
        card.clipToOutline = true
        card.outlineProvider = ViewOutlineProvider.BACKGROUND
    }

    override fun frostCard(card: MaterialCardView, dark: Boolean) {
        val scheme = ExpressivePalette.resolve(card, dark)
        val d = card.resources.displayMetrics.density
        // Remove any SeaGlass sheen overlay if previously added
        (card.getTag(R.id.glass_tint) as? Drawable)?.let {
            card.overlay.remove(it)
            card.setTag(R.id.glass_tint, null)
        }
        card.setCardBackgroundColor(scheme.surfaceContainerLow)
        card.radius = 22f * d
        card.strokeColor = ColorUtils.setAlphaComponent(scheme.outlineVariant, if (dark) 0x66 else 0x80)
        card.strokeWidth = (1f * d).toInt().coerceAtLeast(1)
        card.cardElevation = 0f
    }

    override fun styleSelectableCard(card: MaterialCardView, isSelected: Boolean, dark: Boolean) {
        val scheme = ExpressivePalette.resolve(card, dark)
        val d = card.resources.displayMetrics.density
        // Same base as the main page's settings cards (surfaceContainerLow + outlineVariant hairline);
        // the selected state is only a gentle step towards primaryContainer, not a different colour.
        if (isSelected) {
            card.setCardBackgroundColor(
                ColorUtils.blendARGB(scheme.surfaceContainerLow, scheme.primaryContainer, if (dark) 0.28f else 0.36f)
            )
            card.strokeColor = ColorUtils.setAlphaComponent(scheme.primary, if (dark) 0x99 else 0x8C)
            card.strokeWidth = (1.5f * d + 0.5f).toInt()
        } else {
            card.setCardBackgroundColor(scheme.surfaceContainerLow)
            card.strokeColor = ColorUtils.setAlphaComponent(scheme.outlineVariant, if (dark) 0x70 else 0x99)
            card.strokeWidth = (1f * d + 0.5f).toInt()
        }
    }

    override fun applyChoiceButtonState(button: MaterialButton, isSelected: Boolean, dark: Boolean) {
        button.isSelected = isSelected
        val scheme = ExpressivePalette.resolve(button, dark)
        val d = button.resources.displayMetrics.density
        val strokeWidthPx = (1.2f * d).toInt().coerceAtLeast(1)

        if (isSelected) {
            button.backgroundTintList = ColorStateList.valueOf(scheme.primary)
            val textColor =
                if (button.isEnabled || ColorUtils.calculateLuminance(scheme.onPrimary) < 0.5) scheme.onPrimary
                else scheme.onSurface
            button.setTextColor(textColor)
            button.iconTint = ColorStateList.valueOf(textColor)
            button.strokeColor = ColorStateList.valueOf(scheme.primary)
            button.strokeWidth = strokeWidthPx
            button.elevation = 2f * d
        } else {
            button.backgroundTintList = ColorStateList.valueOf(scheme.secondaryContainer)
            button.setTextColor(scheme.onSecondaryContainer)
            button.iconTint = ColorStateList.valueOf(scheme.onSecondaryContainer)
            button.strokeColor = ColorStateList.valueOf(
                ColorUtils.setAlphaComponent(scheme.outlineVariant, 0x99)
            )
            button.strokeWidth = strokeWidthPx
            button.elevation = 0f
        }
    }

    override fun styleIndicatorDot(dot: View, active: Boolean, dark: Boolean) {
        val scheme = ExpressivePalette.resolve(dot, dark)
        val d = dot.resources.displayMetrics.density
        dot.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = if (active) 4f * d else 3f * d
            setColor(if (active) scheme.primary else ColorUtils.setAlphaComponent(scheme.outlineVariant, 0xCC))
        }
    }

    override fun stylePhraseItemBackground(view: View, dragging: Boolean) {
        val ov = ExpressivePalette.resolveOverlay(view.context)
        val d = view.resources.displayMetrics.density
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = (if (dragging) 18f else 16f) * d
            setColor(if (dragging) ov.phraseItemDragFill else ov.phraseItemFill)
            setStroke(
                ((if (dragging) 1.5f else 1f) * d).toInt().coerceAtLeast(1),
                if (dragging) ov.phraseItemDragStroke else ov.phraseItemStroke
            )
        }
    }

    override fun styleDeleteZoneBackground(view: View, active: Boolean) {
        val ov = ExpressivePalette.resolveOverlay(view.context)
        val d = view.resources.displayMetrics.density
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = (if (active) 22f else 28f) * d
            setColor(if (active) ov.deleteZoneActiveFill else ov.deleteZoneFill)
            setStroke(
                ((if (active) 2.5f else 1.5f) * d).toInt().coerceAtLeast(1),
                if (active) ov.deleteZoneActiveStroke else ov.deleteZoneStroke
            )
        }
    }

    override fun styleCapsuleIndicator(view: View) {
        val d = view.resources.displayMetrics.density
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 12f * d
            setColor(0x38FFFFFF)
            setStroke((1.5f * d).toInt().coerceAtLeast(1), 0x99FFFFFF.toInt())
        }
    }

    override fun stylePulseViews(ring: View?, wave: View?) {
        val d = (ring ?: wave)?.resources?.displayMetrics?.density ?: return
        val makeDrawable = {
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 16f * d
                setColor(0x2EE53935)
                setStroke((2.5f * d).toInt().coerceAtLeast(1), 0xD9FF8A80.toInt())
            }
        }
        ring?.background = makeDrawable()
        wave?.background = makeDrawable()
    }

    override fun installEntranceAndPressMotion(root: ViewGroup) {
        val d = root.resources.displayMetrics.density
        val cards = ArrayList<View>()
        collectGlassCards(root, cards)
        val visible = cards.filter { it.visibility == View.VISIBLE }.take(8)
        visible.forEachIndexed { i, v ->
            val finalAlpha = v.alpha
            v.alpha = 0f
            v.translationY = 24f * d
            v.scaleX = 0.96f
            v.scaleY = 0.96f
            v.animate()
                .alpha(finalAlpha)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setStartDelay(25L + i * 40L)
                .setDuration(360L)
                .setInterpolator(OvershootInterpolator(1.1f))
                .start()
        }
        val rows = ArrayList<View>()
        GlassMotion.clickableRows(root, rows)
        rows.forEach { row ->
            var p: android.view.ViewParent? = row.parent
            while (p != null && p !is GlassCard) p = p.parent
            (p as? View)?.let { GlassMotion.press(row, it, 0.975f) }
        }
    }

    private fun collectGlassCards(v: View, out: MutableList<View>) {
        if (v is GlassCard) out.add(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) collectGlassCards(v.getChildAt(i), out)
    }
}
