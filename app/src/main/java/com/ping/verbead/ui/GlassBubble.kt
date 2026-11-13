package com.ping.verbead.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.luminance
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.ping.verbead.R
import com.ping.verbead.ui.style.UiStyle
import com.ping.verbead.ui.style.UiStyleManager
import dev.mttmcknn.seaglass.SeaGlassMaterialTheme
import dev.mttmcknn.seaglass.SeaGlassPalette
import dev.mttmcknn.seaglass.SeaGlassShape
import dev.mttmcknn.seaglass.rememberSeaGlassColorScheme
import dev.mttmcknn.seaglass.seaGlass

/** Small, tight glow so it fits inside the bubble's small overlay window. */
internal val BubbleRecipe = SmallGlassRecipe.copy(thickness = 2.dp, frost = 0.25f, bulge = 0.25f)

/** Content colour for a glass body: near-black on a light body, white on a dark one. */
internal fun panelContentTint(body: Color): android.content.res.ColorStateList =
    android.content.res.ColorStateList.valueOf(
        if (body.luminance() > 0.5f) 0xFF14181C.toInt() else 0xFFFFFFFF.toInt()
    )

internal object OverlayOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val controller = SavedStateRegistryController.create(this)

    init {
        controller.performAttach()
        controller.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
    }

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry
}

/**
 * The floating bubble's button rendered in the active [UiStyle]:
 * - [UiStyle.EXPRESSIVE]: Material 3 Expressive layered squircle-disc that morphs to a sharper
 *   active squircle with a bouncy spring when listening.
 * - [UiStyle.SEA_GLASS]: Translucent Sea Glass disc following the Material You wallpaper seed.
 */
class GlassBubbleLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val activeState = mutableStateOf(false)

    /** ARGB of the coloured shadow Sea Glass puts under the ball, 0 when the style has none. */
    var glowColor: Int = 0
        private set
    /** Bumped when the screen orientation, UI style, or size changes. */
    private val rebuild = androidx.compose.runtime.mutableIntStateOf(0)

    init {
        clipChildren = false
        clipToPadding = false
        val glass = ComposeView(context).apply {
            setContent {
                val style = UiStyleManager.currentStyle(context)
                val dark = isSystemInDarkTheme()
                val colors = rememberSeaGlassColorScheme(
                    dynamicColor = true,
                    darkTheme = dark
                )
                MaterialTheme(colorScheme = colors) {
                    val cs = MaterialTheme.colorScheme
                    val mix = remember { Animatable(0f) }
                    val active by activeState
                    LaunchedEffect(active) { mix.animateTo(if (active) 1f else 0f, tween(220)) }
                    val generation by rebuild

                    if (style == UiStyle.EXPRESSIVE) {
                        val corner by animateDpAsState(
                            targetValue = if (active) 16.dp else 21.dp,
                            animationSpec = spring(dampingRatio = 0.52f, stiffness = 380f),
                            label = "m3e-bubble-corner"
                        )
                        val idleStart = if (dark) lerp(cs.primaryContainer, cs.primary, 0.35f) else lerp(cs.primary, cs.primaryContainer, 0.18f)
                        val idleEnd = if (dark) lerp(cs.primaryContainer, Color.Black, 0.22f) else lerp(cs.primary, Color.Black, 0.16f)
                        val idleRim = if (dark) cs.primary.copy(alpha = 0.55f) else cs.primaryContainer.copy(alpha = 0.72f)
                        val activeStart = if (dark) Color(0xFFD32F2F) else Color(0xFFE53935)
                        val activeEnd = if (dark) Color(0xFF8E1B1B) else Color(0xFFB3261E)
                        val activeRim = Color(0xFFFF8A80).copy(alpha = 0.68f)

                        val startColor = lerp(idleStart, activeStart, mix.value)
                        val endColor = lerp(idleEnd, activeEnd, mix.value)
                        val outerRim = lerp(idleRim, activeRim, mix.value)
                        // Judge by the resting colour, not the one mid-fade, or leaving the
                        // listening state flashes white before the dark icon settles.
                        val idleBody = lerp(idleStart, idleEnd, 0.5f)
                        SideEffect { applyIconTint(idleBody, active); glowColor = 0 }
                        val outerShape = RoundedCornerShape(corner)
                        val innerShape = RoundedCornerShape((corner - 4.dp).coerceAtLeast(10.dp))
                        androidx.compose.runtime.key(generation) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .clip(outerShape)
                                    .background(Brush.linearGradient(listOf(startColor, endColor)))
                                    .border(1.5.dp, outerRim, outerShape)
                                    .padding(4.dp)
                                    .border(1.dp, Color.White.copy(alpha = 0.22f), innerShape)
                            )
                        }
                    } else {
                        val idle = if (dark) cs.primaryContainer else cs.primary
                        val listening = if (dark) Color(0xFF8E1B1B) else Color(0xFFB3261E)
                        val body = lerp(idle, listening, mix.value)
                        val palette = SeaGlassPalette(body, lerp(body, Color.White, .35f), Color.White)
                        SideEffect { applyIconTint(idle, active) }
                        // The coloured shadow lives in its own, larger overlay window (see BubbleGlowWindow);
                        // the layout only reports which colour it should have.
                        val glow = body.copy(alpha = if (dark) 0.75f else 0.9f)
                        SideEffect { glowColor = glow.toArgb() }
                        androidx.compose.runtime.key(generation) {
                            SeaGlassMaterialTheme(style = BubbleRecipe, shape = SeaGlassShape.Disc) {
                                Box(Modifier.fillMaxSize().clip(CircleShape).seaGlass(palette = palette))
                            }
                        }
                    }
                }
            }
        }
        addView(glass, 0, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /**
     * Icons follow the bubble's body colour: white on a dark body, near-black on a light one.
     * While listening the red microphone keeps its own colour.
     */
    private fun applyIconTint(idleBody: Color, listening: Boolean) {
        val tint = if (listening) null else panelContentTint(idleBody)
        idleTint = panelContentTint(idleBody)
        setIconTint(tint)
    }

    private var idleTint: android.content.res.ColorStateList? = null

    private fun setIconTint(tint: android.content.res.ColorStateList?) {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            (child as? android.widget.ImageView)?.imageTintList = tint
            // The loading spinner sits on the idle body, the transcribing one on the red body.
            (child as? android.widget.ProgressBar)?.indeterminateTintList =
                tint ?: android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
        }
    }

    override fun onAttachedToWindow() {
        val root = rootView
        if (root.getTag(androidx.lifecycle.runtime.R.id.view_tree_lifecycle_owner) == null) {
            root.setViewTreeLifecycleOwner(OverlayOwner)
        }
        if (root.getTag(androidx.savedstate.R.id.view_tree_saved_state_registry_owner) == null) {
            root.setViewTreeSavedStateRegistryOwner(OverlayOwner)
        }
        super.onAttachedToWindow()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration?) {
        super.onConfigurationChanged(newConfig)
        rebuild.intValue++
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (oldw != 0 || oldh != 0) rebuild.intValue++
    }

    /** Called by the service after a configuration or UI style change. */
    fun rebuildGlass() {
        rebuild.intValue++
    }

    override fun setBackgroundResource(resid: Int) {
        val active = resid == R.drawable.bubble_background_active
        activeState.value = active
        // Apply right away instead of waiting for the next composition frame, so the icon
        // never shows the old tint for a moment when the state flips.
        setIconTint(if (active) null else idleTint)
    }
}
