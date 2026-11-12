package com.ping.verbead.util

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/** In-app light / dark choice. It only affects this app, never the phone's own display setting. */
object AppTheme {
    const val SYSTEM = "system"
    const val LIGHT = "light"
    const val DARK = "dark"
    val MODES = listOf(SYSTEM, LIGHT, DARK)

    private const val PREFS = "app_theme"
    private const val KEY = "mode"

    fun current(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, SYSTEM) ?: SYSTEM

    /** Applies the saved choice; call once at start-up. */
    fun restore(context: Context) = apply(current(context))

    fun set(context: Context, mode: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, mode).apply()
        apply(mode)
    }

    private fun apply(mode: String) = AppCompatDelegate.setDefaultNightMode(
        when (mode) {
            LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            DARK -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
    )
}
