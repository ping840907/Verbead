package com.ping.verbead.ui.style

import android.content.Context
import android.graphics.Color
import android.os.Build
import android.view.View
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.ping.verbead.R

/**
 * Tonal palette tokens for the Material 3 Expressive theme across light, dark, and
 * always-dark floating overlay surfaces, blending dynamic Material You colors when available.
 */
object ExpressivePalette {

    data class TonalScheme(
        val surface: Int,
        val surfaceContainerLowest: Int,
        val surfaceContainerLow: Int,
        val surfaceContainer: Int,
        val surfaceContainerHigh: Int,
        val surfaceContainerHighest: Int,
        val primary: Int,
        val onPrimary: Int,
        val primaryContainer: Int,
        val onPrimaryContainer: Int,
        val secondary: Int,
        val secondaryContainer: Int,
        val onSecondaryContainer: Int,
        val tertiary: Int,
        val tertiaryContainer: Int,
        val onTertiaryContainer: Int,
        val onSurface: Int,
        val onSurfaceVariant: Int,
        val outline: Int,
        val outlineVariant: Int,
        val error: Int,
        val errorContainer: Int
    )

    val Light = TonalScheme(
        surface = 0xFFFBF8FF.toInt(),
        surfaceContainerLowest = 0xFFFFFFFF.toInt(),
        surfaceContainerLow = 0xFFF3EFFA.toInt(),
        surfaceContainer = 0xFFEDE7F6.toInt(),
        surfaceContainerHigh = 0xFFE6E0F0.toInt(),
        surfaceContainerHighest = 0xFFDFD8EB.toInt(),
        primary = 0xFF0061A4.toInt(),
        onPrimary = 0xFFFFFFFF.toInt(),
        primaryContainer = 0xFFD1E4FF.toInt(),
        onPrimaryContainer = 0xFF001D36.toInt(),
        secondary = 0xFF535F70.toInt(),
        secondaryContainer = 0xFFD7E3F7.toInt(),
        onSecondaryContainer = 0xFF101C2B.toInt(),
        tertiary = 0xFF6B5778.toInt(),
        tertiaryContainer = 0xFFF2DAFF.toInt(),
        onTertiaryContainer = 0xFF251431.toInt(),
        onSurface = 0xFF191C20.toInt(),
        onSurfaceVariant = 0xFF43474E.toInt(),
        outline = 0xFF73777F.toInt(),
        outlineVariant = 0xFFC3C7CF.toInt(),
        error = 0xFFBA1A1A.toInt(),
        errorContainer = 0xFFFFDAD6.toInt()
    )

    val Dark = TonalScheme(
        surface = 0xFF10131B.toInt(),
        surfaceContainerLowest = 0xFF0B0E14.toInt(),
        surfaceContainerLow = 0xFF181C26.toInt(),
        surfaceContainer = 0xFF1D222E.toInt(),
        surfaceContainerHigh = 0xFF272C3A.toInt(),
        surfaceContainerHighest = 0xFF323846.toInt(),
        primary = 0xFF9ECAFF.toInt(),
        onPrimary = 0xFF003258.toInt(),
        primaryContainer = 0xFF00497D.toInt(),
        onPrimaryContainer = 0xFFD1E4FF.toInt(),
        secondary = 0xFFBBC7DB.toInt(),
        secondaryContainer = 0xFF3B4858.toInt(),
        onSecondaryContainer = 0xFFD7E3F7.toInt(),
        tertiary = 0xFFD6BEE4.toInt(),
        tertiaryContainer = 0xFF523F5F.toInt(),
        onTertiaryContainer = 0xFFF2DAFF.toInt(),
        onSurface = 0xFFE2E2E9.toInt(),
        onSurfaceVariant = 0xFFC3C7CF.toInt(),
        outline = 0xFF8D9199.toInt(),
        outlineVariant = 0xFF43474E.toInt(),
        error = 0xFFFFB4AB.toInt(),
        errorContainer = 0xFF93000A.toInt()
    )

    data class OverlayScheme(
        val panelSurface: Int,
        val panelHeaderTint: Int,
        val panelStroke: Int,
        val phraseItemFill: Int,
        val phraseItemStroke: Int,
        val phraseItemDragFill: Int,
        val phraseItemDragStroke: Int,
        val capsuleIndicatorFill: Int,
        val capsuleIndicatorStroke: Int,
        val deleteZoneFill: Int,
        val deleteZoneStroke: Int,
        val deleteZoneActiveFill: Int = 0xF0C62828.toInt(),
        val deleteZoneActiveStroke: Int = 0xFFFF8A80.toInt()
    )

    fun resolveOverlay(context: Context): OverlayScheme {
        val accentLight: Int
        val accentMid: Int
        val accentDeep: Int
        val neutralDark: Int
        if (Build.VERSION.SDK_INT >= 31) {
            accentLight = runCatching { context.getColor(android.R.color.system_accent1_200) }.getOrDefault(Dark.primary)
            accentMid = runCatching { context.getColor(android.R.color.system_accent1_600) }.getOrDefault(Light.primary)
            accentDeep = runCatching { context.getColor(android.R.color.system_accent1_900) }.getOrDefault(Dark.primaryContainer)
            neutralDark = runCatching { context.getColor(android.R.color.system_neutral1_900) }.getOrDefault(Dark.surface)
        } else {
            val themed = DynamicColors.wrapContextIfAvailable(ContextThemeWrapper(context, R.style.Theme_Verbead))
            accentLight = MaterialColors.getColor(themed, androidx.appcompat.R.attr.colorPrimary, Dark.primary)
            accentMid = MaterialColors.getColor(themed, com.google.android.material.R.attr.colorPrimaryContainer, Dark.primaryContainer)
            accentDeep = ColorUtils.blendARGB(accentMid, Color.BLACK, 0.45f)
            neutralDark = Dark.surface
        }
        val basePanel = ColorUtils.blendARGB(neutralDark, accentDeep, 0.32f)
        return OverlayScheme(
            panelSurface = ColorUtils.setAlphaComponent(basePanel, 0xF4),
            panelHeaderTint = ColorUtils.setAlphaComponent(accentLight, 0x26),
            panelStroke = ColorUtils.setAlphaComponent(accentLight, 0x52),
            phraseItemFill = ColorUtils.setAlphaComponent(accentLight, 0x2B),
            phraseItemStroke = ColorUtils.setAlphaComponent(accentLight, 0x52),
            phraseItemDragFill = ColorUtils.setAlphaComponent(accentMid, 0x66),
            phraseItemDragStroke = ColorUtils.setAlphaComponent(accentLight, 0xCC),
            capsuleIndicatorFill = ColorUtils.setAlphaComponent(accentLight, 0x42),
            capsuleIndicatorStroke = ColorUtils.setAlphaComponent(accentLight, 0x88),
            deleteZoneFill = ColorUtils.setAlphaComponent(basePanel, 0xE8),
            deleteZoneStroke = ColorUtils.setAlphaComponent(accentLight, 0x7A)
        )
    }

    /** Resolves an expressive tonal scheme, incorporating dynamic Material colors from [view]. */
    fun resolve(view: View, dark: Boolean): TonalScheme {
        val fallback = if (dark) Dark else Light
        val dynPrimary = MaterialColors.getColor(view, androidx.appcompat.R.attr.colorPrimary, fallback.primary)
        val dynOnPrimary = MaterialColors.getColor(view, com.google.android.material.R.attr.colorOnPrimary, fallback.onPrimary)
        val dynPrimaryContainer = MaterialColors.getColor(
            view, com.google.android.material.R.attr.colorPrimaryContainer, fallback.primaryContainer
        )
        val dynSecondaryContainer = MaterialColors.getColor(
            view, com.google.android.material.R.attr.colorSecondaryContainer, fallback.secondaryContainer
        )
        val dynTertiary = MaterialColors.getColor(view, com.google.android.material.R.attr.colorTertiary, fallback.tertiary)
        val dynTertiaryContainer = MaterialColors.getColor(
            view, com.google.android.material.R.attr.colorTertiaryContainer, fallback.tertiaryContainer
        )
        val dynSurface = MaterialColors.getColor(view, com.google.android.material.R.attr.colorSurface, fallback.surface)
        val dynOnSurface = MaterialColors.getColor(view, com.google.android.material.R.attr.colorOnSurface, fallback.onSurface)
        val dynOutlineVariant = MaterialColors.getColor(
            view, com.google.android.material.R.attr.colorOutlineVariant, fallback.outlineVariant
        )

        val surface = ColorUtils.blendARGB(dynSurface, fallback.surface, 0.35f)
        val surfaceLow = ColorUtils.blendARGB(surface, dynPrimaryContainer, if (dark) 0.14f else 0.18f)
        val surfaceContainer = ColorUtils.blendARGB(surface, dynPrimaryContainer, if (dark) 0.20f else 0.25f)
        val surfaceHigh = ColorUtils.blendARGB(surface, dynPrimaryContainer, if (dark) 0.28f else 0.32f)
        val surfaceHighest = ColorUtils.blendARGB(surface, dynPrimaryContainer, if (dark) 0.35f else 0.38f)

        return fallback.copy(
            surface = surface,
            surfaceContainerLow = surfaceLow,
            surfaceContainer = surfaceContainer,
            surfaceContainerHigh = surfaceHigh,
            surfaceContainerHighest = surfaceHighest,
            primary = dynPrimary,
            onPrimary = dynOnPrimary,
            primaryContainer = dynPrimaryContainer,
            secondaryContainer = dynSecondaryContainer,
            tertiary = dynTertiary,
            tertiaryContainer = dynTertiaryContainer,
            onSurface = dynOnSurface,
            outlineVariant = if (Color.alpha(dynOutlineVariant) > 0) dynOutlineVariant else fallback.outlineVariant
        )
    }
}
