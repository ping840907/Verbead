package com.ping.verbead.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.util.AttributeSet
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.ping.verbead.R
import com.ping.verbead.ui.style.UiStyle
import com.ping.verbead.ui.style.UiStyleManager
import dev.mttmcknn.seaglass.SeaGlassMaterialTheme
import dev.mttmcknn.seaglass.SeaGlassPalette
import dev.mttmcknn.seaglass.SeaGlassShape
import dev.mttmcknn.seaglass.rememberSeaGlassColorScheme
import dev.mttmcknn.seaglass.seaGlass

/**
 * MaterialButton whose own fill, stroke and shadow are switched off once [GlassStage] has wrapped
 * it in a [GlassButtonFrame]. Existing code keeps using it as a MaterialButton (text, icon,
 * backgroundTintList, strokeColor ...). The tint it is given becomes the fill pigment for both
 * [UiStyle.EXPRESSIVE] (morphing squircle-pill container) and [UiStyle.SEA_GLASS] (glass capsule).
 */
class GlassMaterialButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.materialButtonStyle
) : MaterialButton(context, attrs, defStyleAttr) {

    internal var frame: GlassButtonFrame? = null

    /** Pigment colour: the last background tint that was requested. */
    internal var glassTint: Int
        get() = (getTag(R.id.glass_tint) as? Int) ?: Color.TRANSPARENT
        set(value) = setTag(R.id.glass_tint, value)

    @SuppressLint("RestrictedApi")
    override fun setSupportBackgroundTintList(tint: ColorStateList?) {
        val f = frame
        if (f == null) {
            super.setSupportBackgroundTintList(tint)
            return
        }
        glassTint = enabledColor(tint)
        f.onTintChanged(glassTint)
        super.setSupportBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT))
    }

    override fun setStrokeWidth(strokeWidth: Int) {
        super.setStrokeWidth(if (frame != null) 0 else strokeWidth)
    }

    override fun setElevation(elevation: Float) {
        super.setElevation(if (frame != null) 0f else elevation)
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        frame?.onEnabledChanged(enabled)
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        frame?.onPressedChanged(pressed)
    }

    override fun setSelected(selected: Boolean) {
        super.setSelected(selected)
        frame?.onSelectedChanged(selected)
    }

    override fun setVisibility(visibility: Int) {
        super.setVisibility(visibility)
        frame?.visibility = visibility
    }

    private fun enabledColor(tint: ColorStateList?): Int =
        tint?.getColorForState(intArrayOf(android.R.attr.state_enabled), tint.defaultColor) ?: Color.TRANSPARENT

    @SuppressLint("RestrictedApi")
    internal fun neutralise() {
        val initial = enabledColor(supportBackgroundTintList)
        glassTint = initial
        strokeWidth = 0
        super.setSupportBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT))
        super.setElevation(0f)
    }
}

/** Hosts the active theme background layer behind a [GlassMaterialButton]. */
class GlassButtonFrame(context: Context, private val button: GlassMaterialButton) : FrameLayout(context) {

    private val tintState = mutableIntStateOf(Color.TRANSPARENT)
    private val enabledState = mutableStateOf(true)
    private val pressedState = mutableStateOf(false)
    private val selectedState = mutableStateOf(false)
    private val surfaceVariant = MaterialColors.getColor(
        button, com.google.android.material.R.attr.colorSurfaceVariant, Color.GRAY
    )

    private val glow = (GLOW_DP * resources.displayMetrics.density).toInt()
    private val glassView: ComposeView

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        measureChildWithMargins(button, widthMeasureSpec, 0, heightMeasureSpec, 0)
        val w = resolveSize(button.measuredWidth, widthMeasureSpec)
        val h = resolveSize(button.measuredHeight, heightMeasureSpec)
        setMeasuredDimension(w, h)
        glassView.measure(
            MeasureSpec.makeMeasureSpec(w + 2 * glow, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(h + 2 * glow, MeasureSpec.EXACTLY)
        )
    }

    init {
        clipChildren = false
        clipToPadding = false
        glassView = ComposeView(context).apply {
            setContent {
                val style = UiStyleManager.currentStyle(context)
                val dark = isSystemInDarkTheme()
                val colors = rememberSeaGlassColorScheme(
                    dynamicColor = true,
                    darkTheme = dark
                )
                MaterialTheme(colorScheme = colors) {
                    val tint by tintState
                    val on by enabledState
                    val pressed by pressedState
                    val selected by selectedState

                    if (style == UiStyle.EXPRESSIVE) {
                        val cs = MaterialTheme.colorScheme
                        val hasFill = Color.alpha(tint) > 0
                        val targetFill = when {
                            hasFill -> {
                                val raw = ComposeColor(tint or (0xFF shl 24))
                                if (pressed) {
                                    lerp(raw, if (dark) ComposeColor.White else ComposeColor.Black, 0.12f)
                                } else raw
                            }
                            pressed -> cs.primary.copy(alpha = 0.18f)
                            else -> cs.secondaryContainer.copy(alpha = 0.55f)
                        }
                        val fillColor by animateColorAsState(
                            targetValue = targetFill,
                            animationSpec = spring(stiffness = 700f),
                            label = "m3e-btn-fill"
                        )
                        val strokeTarget = when {
                            !hasFill -> cs.outlineVariant.copy(alpha = 0.85f)
                            selected -> cs.primary
                            !dark && ColorUtils.calculateLuminance(tint or (0xFF shl 24)) > 0.72 ->
                                cs.outlineVariant.copy(alpha = 0.8f)
                            dark && ColorUtils.calculateLuminance(tint or (0xFF shl 24)) < 0.25 ->
                                cs.outlineVariant.copy(alpha = 0.7f)
                            else -> ComposeColor.Transparent
                        }
                        val cornerRadius by animateDpAsState(
                            targetValue = when {
                                pressed -> 10.dp
                                selected -> 14.dp
                                else -> 24.dp
                            },
                            animationSpec = spring(dampingRatio = 0.55f, stiffness = 550f),
                            label = "m3e-btn-shape"
                        )
                        val shape = RoundedCornerShape(cornerRadius)
                        Box(
                            Modifier
                                .fillMaxSize()
                                .alpha(if (on) 1f else 0.45f)
                                .padding(GLOW_DP.dp)
                                .clip(shape)
                                .background(fillColor)
                                .border(1.dp, strokeTarget, shape)
                        )
                    } else {
                        val bodyArgb = if (Color.alpha(tint) > 0) tint else surfaceVariant
                        val light = !dark
                        val base = ComposeColor(bodyArgb or (0xFF shl 24))
                        val body = if (light) lerp(base, MaterialTheme.colorScheme.onSurface, 0.14f)
                        else lerp(base, ComposeColor.White, 0.28f)
                        val palette = SeaGlassPalette(body, lerp(body, ComposeColor.White, .35f), ComposeColor.Black)
                        val rim = if (light) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f) else ComposeColor.Transparent
                        SeaGlassMaterialTheme(style = SmallGlassRecipe, shape = SeaGlassShape.Capsule) {
                            Box(
                                Modifier.fillMaxSize()
                                    .alpha(if (on) 1f else 0.45f)
                                    .padding(GLOW_DP.dp)
                                    .clip(RoundedCornerShape(50))
                                    .seaGlass(palette = palette)
                                    .border(1.dp, rim, RoundedCornerShape(50))
                            )
                        }
                    }
                }
            }
        }
        addView(
            glassView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
                setMargins(-glow, -glow, -glow, -glow)
            }
        )
        tintState.intValue = button.glassTint
        enabledState.value = button.isEnabled
        pressedState.value = button.isPressed
        selectedState.value = button.isSelected
    }

    internal fun onTintChanged(tint: Int) {
        tintState.intValue = tint
    }

    internal fun onEnabledChanged(enabled: Boolean) {
        enabledState.value = enabled
    }

    internal fun onPressedChanged(pressed: Boolean) {
        pressedState.value = pressed
    }

    internal fun onSelectedChanged(selected: Boolean) {
        selectedState.value = selected
    }

    companion object {
        const val GLOW_DP = 2

        /** Replaces [button] in its parent by a frame that holds the themed background and the button. */
        fun wrap(button: GlassMaterialButton) {
            val parent = button.parent as? ViewGroup ?: return
            if (button.frame != null) return
            val index = parent.indexOfChild(button)
            val lp = button.layoutParams
            val frame = GlassButtonFrame(button.context, button)
            parent.removeViewAt(index)
            frame.visibility = button.visibility
            parent.addView(frame, index, lp)
            val wrapH = lp.height == ViewGroup.LayoutParams.WRAP_CONTENT
            val wrapW = lp.width == ViewGroup.LayoutParams.WRAP_CONTENT
            frame.addView(
                button,
                LayoutParams(
                    if (wrapW) LayoutParams.WRAP_CONTENT else LayoutParams.MATCH_PARENT,
                    if (wrapH) LayoutParams.WRAP_CONTENT else LayoutParams.MATCH_PARENT
                )
            )
            button.frame = frame
            button.neutralise()
            GlassMotion.press(button, frame, 0.95f)
            frame.tintState.intValue = button.glassTint
        }
    }
}
