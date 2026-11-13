package com.ping.verbead.ui.style

import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.ping.verbead.ui.GlassCard
import com.ping.verbead.util.AppTheme

/** Supported visual design systems for the app and its floating overlays. */
enum class UiStyle(val key: String) {
    EXPRESSIVE(AppTheme.STYLE_EXPRESSIVE),
    SEA_GLASS(AppTheme.STYLE_SEA_GLASS);

    companion object {
        fun fromKey(key: String?): UiStyle =
            if (key == SEA_GLASS.key) SEA_GLASS else EXPRESSIVE
    }
}

/**
 * Modular contract implemented by each UI style ([ExpressiveStyleModule], [SeaGlassStyleModule]).
 * View wrappers and controllers delegate theme-specific styling and motion here without duplicating
 * style branching across activities.
 */
interface UiStyleModule {
    val style: UiStyle

    fun drawBackdrop(view: View, canvas: Canvas, width: Int, height: Int, dark: Boolean)

    fun styleCard(card: GlassCard, dark: Boolean)

    fun frostCard(card: MaterialCardView, dark: Boolean)

    fun styleSelectableCard(card: MaterialCardView, isSelected: Boolean, dark: Boolean)

    fun applyChoiceButtonState(button: MaterialButton, isSelected: Boolean, dark: Boolean)

    fun styleIndicatorDot(dot: View, active: Boolean, dark: Boolean)

    fun stylePhraseItemBackground(view: View, dragging: Boolean)

    fun styleDeleteZoneBackground(view: View, active: Boolean)

    fun styleCapsuleIndicator(view: View)

    fun stylePulseViews(ring: View?, wave: View?)

    fun installEntranceAndPressMotion(root: ViewGroup)
}
