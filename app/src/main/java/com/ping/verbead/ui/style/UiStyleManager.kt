package com.ping.verbead.ui.style

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ping.verbead.util.AppTheme

/**
 * Central observable dispatcher for the active [UiStyleModule].
 * Compose views read [styleState] via [currentStyle] so style toggles recompose immediately.
 */
object UiStyleManager {
    var styleState by mutableStateOf(UiStyle.EXPRESSIVE)
        private set

    fun syncFromPrefs(context: Context): UiStyle {
        val resolved = UiStyle.fromKey(AppTheme.getStyle(context))
        if (styleState != resolved) styleState = resolved
        return resolved
    }

    fun notifyStyleChanged(styleKey: String) {
        val next = UiStyle.fromKey(styleKey)
        if (styleState != next) styleState = next
    }

    fun currentStyle(context: Context): UiStyle {
        val pref = UiStyle.fromKey(AppTheme.getStyle(context))
        if (styleState != pref) styleState = pref
        return styleState
    }

    fun current(context: Context): UiStyleModule = when (currentStyle(context)) {
        UiStyle.EXPRESSIVE -> ExpressiveStyleModule
        UiStyle.SEA_GLASS -> SeaGlassStyleModule
    }

    fun isDark(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
}
