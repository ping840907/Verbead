package com.ping.verbead.util

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import com.ping.verbead.FloatingBubbleService
import com.ping.verbead.ui.style.UiStyleManager

/** In-app appearance settings: night mode (light / dark / system) and UI style (Material 3 Expressive / SeaGlass). */
object AppTheme {
    const val SYSTEM = "system"
    const val LIGHT = "light"
    const val DARK = "dark"
    val MODES = listOf(SYSTEM, LIGHT, DARK)

    const val STYLE_EXPRESSIVE = "expressive"
    const val STYLE_SEA_GLASS = "sea_glass"
    val STYLES = listOf(STYLE_EXPRESSIVE, STYLE_SEA_GLASS)

    private const val PREFS = "app_theme"
    private const val KEY = "mode"
    private const val STYLE_KEY = "ui_style"

    fun current(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, SYSTEM) ?: SYSTEM

    fun getStyle(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(STYLE_KEY, STYLE_EXPRESSIVE)
            ?: STYLE_EXPRESSIVE

    fun isExpressive(context: Context): Boolean = getStyle(context) == STYLE_EXPRESSIVE

    /** Applies the saved choices; call once at start-up. */
    fun restore(context: Context) {
        apply(current(context))
        UiStyleManager.syncFromPrefs(context)
    }

    fun set(context: Context, mode: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, mode).apply()
        apply(mode)
    }

    fun setStyle(context: Context, style: String) {
        val normalized = if (style == STYLE_SEA_GLASS) STYLE_SEA_GLASS else STYLE_EXPRESSIVE
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(STYLE_KEY, normalized).apply()
        UiStyleManager.notifyStyleChanged(normalized)
        FloatingBubbleService.notifyThemeStyleChanged()
    }

    private fun apply(mode: String) = AppCompatDelegate.setDefaultNightMode(
        when (mode) {
            LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            DARK -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
    )
}
