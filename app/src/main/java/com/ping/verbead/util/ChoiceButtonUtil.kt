package com.ping.verbead.util

import android.content.res.ColorStateList
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.ping.verbead.R

object ChoiceButtonUtil {
    /**
     * Styles a segmented choice button (tab/pill) with high-contrast active and inactive states.
     * Inactive state features a card-surface background with a crisp border so it never blends
     * into container backgrounds.
     */
    fun applyChoiceState(
        button: MaterialButton,
        isSelected: Boolean
    ) {
        val context = button.context
        val primaryColor = ContextCompat.getColor(context, R.color.md_theme_light_primary)
        val onPrimaryColor = ContextCompat.getColor(context, R.color.md_theme_light_onPrimary)
        val unselectedBg = ContextCompat.getColor(context, R.color.surface_card)
        val unselectedText = ContextCompat.getColor(context, R.color.text_primary)
        val strokeColor = if (isSelected) primaryColor else ContextCompat.getColor(context, R.color.surface_card_stroke)
        val density = context.resources.displayMetrics.density
        val strokeWidthPx = (1.2f * density).toInt().coerceAtLeast(1)

        if (isSelected) {
            button.backgroundTintList = ColorStateList.valueOf(primaryColor)
            button.setTextColor(onPrimaryColor)
            button.iconTint = ColorStateList.valueOf(onPrimaryColor)
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
}
