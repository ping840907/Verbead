package com.ping.verbead.ui

import android.content.Context
import android.graphics.Color
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import dev.mttmcknn.seaglass.SeaGlassMaterialTheme
import dev.mttmcknn.seaglass.SeaGlassPalette
import dev.mttmcknn.seaglass.SeaGlassShape
import dev.mttmcknn.seaglass.rememberSeaGlassColorScheme
import dev.mttmcknn.seaglass.seaGlass

/** ChipGroup whose chips are drawn as Sea Glass capsules once [GlassChipFrame] wraps it. */
class GlassChipGroup @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.chipGroupStyle
) : ChipGroup(context, attrs, defStyleAttr)

/** A single chip (e.g. a filter toggle) drawn as a Sea Glass capsule once wrapped. */
class GlassChip @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.chipStyle
) : Chip(context, attrs, defStyleAttr)

private class ChipSpot(
    val left: Float, val top: Float, val width: Float, val height: Float,
    val checked: Boolean, val enabled: Boolean
)

/**
 * Glass layer behind a [GlassChipGroup] or [GlassChip]. The chips keep their own behaviour
 * (selection, listeners, ripple); only their fill and outline are cleared and the glass capsule is
 * drawn at each chip's position.
 */
class GlassChipFrame internal constructor(
    context: Context,
    private val child: View
) : FrameLayout(context) {

    private val spots = mutableStateOf<List<ChipSpot>>(emptyList())
    private val glassView: ComposeView

    init {
        clipChildren = false
        clipToPadding = false
        glassView = ComposeView(context).apply {
            setContent {
                val colors = rememberSeaGlassColorScheme(
                    dynamicColor = true,
                    darkTheme = isSystemInDarkTheme()
                )
                MaterialTheme(colorScheme = colors) {
                    val cs = MaterialTheme.colorScheme
                    val density = LocalDensity.current
                    val list by spots
                    Box(Modifier.fillMaxSize()) {
                        for (s in list) {
                            // Dark enough to keep the chip text readable on top.
                            val body = if (s.checked) lerp(cs.primaryContainer, cs.primary, .35f)
                            else lerp(cs.surfaceVariant, ComposeColor.White, .1f)
                            val palette = SeaGlassPalette(
                                body, lerp(body, ComposeColor.White, .35f), cs.onSurface
                            )
                            SeaGlassMaterialTheme(style = SmallGlassRecipe, shape = SeaGlassShape.Capsule) {
                                Box(
                                    Modifier.align(Alignment.TopStart)
                                        .offset(
                                            x = with(density) { s.left.toDp() },
                                            y = with(density) { s.top.toDp() }
                                        )
                                        .width(with(density) { s.width.toDp() })
                                        .height(with(density) { s.height.toDp() })
                                        .alpha(if (s.enabled) 1f else 0.45f)
                                        .clip(RoundedCornerShape(50))
                                        .seaGlass(palette = palette)
                                )
                            }
                        }
                    }
                }
            }
        }
        addView(glassView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        // Chips can change state or move without this frame being measured again.
        viewTreeObserver.addOnPreDrawListener {
            refreshSpots()
            true
        }
    }

    private fun chips(): List<Chip> = when (child) {
        is ChipGroup -> (0 until child.childCount).mapNotNull { child.getChildAt(it) as? Chip }
        is Chip -> listOf(child)
        else -> emptyList()
    }

    private fun refreshSpots() {
        val next = chips().filter { it.visibility == VISIBLE && it.width > 0 }.map {
            val h = it.chipMinHeight.coerceAtMost(it.height.toFloat())
            val offsetX = if (child is ChipGroup) it.left.toFloat() else 0f
            val offsetY = if (child is ChipGroup) it.top.toFloat() else 0f
            ChipSpot(
                offsetX, offsetY + (it.height - h) / 2f, it.width.toFloat(), h,
                it.isChecked, it.isEnabled
            )
        }
        val cur = spots.value
        val same = cur.size == next.size && cur.indices.all {
            val a = cur[it]; val b = next[it]
            a.left == b.left && a.top == b.top && a.width == b.width && a.height == b.height &&
                a.checked == b.checked && a.enabled == b.enabled
        }
        if (!same) {
            spots.value = next
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, 0)
        val w = resolveSize(child.measuredWidth, widthMeasureSpec)
        val h = resolveSize(child.measuredHeight, heightMeasureSpec)
        setMeasuredDimension(w, h)
        glassView.measure(
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
        )
    }

    internal companion object {
        fun wrap(view: View) {
            val parent = view.parent as? ViewGroup ?: return
            if (parent is GlassChipFrame) return
            val index = parent.indexOfChild(view)
            val lp = view.layoutParams
            val frame = GlassChipFrame(view.context, view)
            parent.removeViewAt(index)
            frame.visibility = view.visibility
            parent.addView(frame, index, lp)
            val wrapW = lp.width == ViewGroup.LayoutParams.WRAP_CONTENT
            frame.addView(
                view,
                LayoutParams(
                    if (wrapW) LayoutParams.WRAP_CONTENT else LayoutParams.MATCH_PARENT,
                    LayoutParams.WRAP_CONTENT
                )
            )
            val chips = when (view) {
                is ChipGroup -> (0 until view.childCount).mapNotNull { view.getChildAt(it) as? Chip }
                is Chip -> listOf(view)
                else -> emptyList()
            }
            chips.forEach {
                it.chipBackgroundColor = ColorStateList.valueOf(Color.TRANSPARENT)
                it.chipStrokeWidth = 0f
            }
        }
    }
}
