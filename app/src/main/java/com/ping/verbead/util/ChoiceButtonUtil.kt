package com.ping.verbead.util

import com.google.android.material.button.MaterialButton
import com.ping.verbead.ui.style.UiStyleManager

object ChoiceButtonUtil {
    /**
     * Styles a segmented choice button (tab/pill) with high-contrast active and inactive states
     * matching the currently selected UI style ([com.ping.verbead.ui.style.UiStyle]).
     */
    fun applyChoiceState(
        button: MaterialButton,
        isSelected: Boolean
    ) {
        val context = button.context
        UiStyleManager.current(context).applyChoiceButtonState(
            button = button,
            isSelected = isSelected,
            dark = UiStyleManager.isDark(context)
        )
    }
}
