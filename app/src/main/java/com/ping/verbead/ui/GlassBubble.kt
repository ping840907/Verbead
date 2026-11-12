package com.ping.verbead.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.ping.verbead.R
import dev.mttmcknn.seaglass.SeaGlassMaterialTheme
import dev.mttmcknn.seaglass.SeaGlassPalette
import dev.mttmcknn.seaglass.SeaGlassShape
import dev.mttmcknn.seaglass.rememberSeaGlassColorScheme
import dev.mttmcknn.seaglass.seaGlass

/**
 * Lifecycle and saved-state owner for Compose inside overlay windows (the floating bubble lives
 * in a WindowManager window, which has no Activity to provide them). Always RESUMED.
 */
/** Small, tight glow so it fits inside the bubble's small overlay window. */
internal val BubbleRecipe = SmallGlassRecipe.copy(thickness = 2.dp, frost = 0.25f, bulge = 0.25f)

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
 * The floating bubble's round button as a Sea Glass disc. Drop-in for the old FrameLayout with a
 * shape background: code that calls `setBackgroundResource(bubble_background[_active])` keeps
 * working, the id only chooses between the normal and the listening (red) glass. The glass is
 * coloured from the Material You scheme, so it follows the wallpaper.
 */
class GlassBubbleLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val activeState = mutableStateOf(false)

    init {
        clipChildren = false
        clipToPadding = false
        val glass = ComposeView(context).apply {
            setContent {
                val colors = rememberSeaGlassColorScheme(
                    dynamicColor = true,
                    darkTheme = isSystemInDarkTheme()
                )
                MaterialTheme(colorScheme = colors) {
                    val cs = MaterialTheme.colorScheme
                    val dark = isSystemInDarkTheme()
                    val mix = remember { Animatable(0f) }
                    val active by activeState
                    LaunchedEffect(active) { mix.animateTo(if (active) 1f else 0f, tween(220)) }
                    // Dark body keeps the white mic icon legible in both themes.
                    val idle = if (dark) cs.primaryContainer else cs.primary
                    val listening = if (dark) Color(0xFF8E1B1B) else Color(0xFFB3261E)
                    val body = lerp(idle, listening, mix.value)
                    val palette = SeaGlassPalette(body, lerp(body, Color.White, .35f), Color.White)
                    SeaGlassMaterialTheme(style = BubbleRecipe, shape = SeaGlassShape.Disc) {
                        // Full 56dp disc. The window has no room for the cast shadow, which would be
                        // cut into a flat edge, so the glass is clipped to the circle instead.
                        Box(Modifier.fillMaxSize().clip(CircleShape).seaGlass(palette = palette))
                    }
                }
            }
        }
        addView(glass, 0, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun onAttachedToWindow() {
        // Overlay windows have no Activity: give the window's root view the owners Compose needs.
        val root = rootView
        if (root.getTag(androidx.lifecycle.runtime.R.id.view_tree_lifecycle_owner) == null) {
            root.setViewTreeLifecycleOwner(OverlayOwner)
        }
        if (root.getTag(androidx.savedstate.R.id.view_tree_saved_state_registry_owner) == null) {
            root.setViewTreeSavedStateRegistryOwner(OverlayOwner)
        }
        super.onAttachedToWindow()
    }

    override fun setBackgroundResource(resid: Int) {
        // Not a real background any more: only record the state.
        activeState.value = resid == R.drawable.bubble_background_active
    }
}
