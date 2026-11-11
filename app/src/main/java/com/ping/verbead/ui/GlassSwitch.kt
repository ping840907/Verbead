package com.ping.verbead.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import dev.mttmcknn.seaglass.SeaGlassPalette
import dev.mttmcknn.seaglass.SeaGlassMaterialTheme
import dev.mttmcknn.seaglass.SeaGlassShape
import dev.mttmcknn.seaglass.SeaGlassStyle
import dev.mttmcknn.seaglass.rememberSeaGlassColorScheme
import dev.mttmcknn.seaglass.seaGlass

/** Quiet recipe with little edge wobble, used for small controls. */
internal val SmallGlassRecipe = SeaGlassStyle.Quiet.copy(
    tumble = 0.008f,
    thickness = 4.dp,
    elevation = 0.dp,
    transmission = 0f
)

/**
 * Sea Glass switch (dev.mttmcknn:sea-glass). Drop-in for GlassSwitch: [isChecked],
 * [setOnCheckedChangeListener], [toggle] and [setEnabled] behave the same. Colours come from the
 * Material You dynamic scheme, so the glass follows the wallpaper.
 */
class GlassSwitch @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val checkedState = mutableStateOf(false)
    private val enabledState = mutableStateOf(true)
    /** 0..1 while the thumb is being dragged by a finger, otherwise null. */
    private val dragFraction = mutableStateOf<Float?>(null)
    private var downX = 0f
    private var dragging = false
    private var startFraction = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var listener: ((View, Boolean) -> Unit)? = null
    private lateinit var glassView: ComposeView

    var isChecked: Boolean
        get() = checkedState.value
        set(value) {
            if (checkedState.value == value) return
            checkedState.value = value
            listener?.invoke(this, value)
        }

    init {
        isClickable = true
        isFocusable = true
        clipChildren = false
        clipToPadding = false
        glassView = ComposeView(context).apply {
            setContent {
                val colors = rememberSeaGlassColorScheme(
                    dynamicColor = true,
                    darkTheme = isSystemInDarkTheme()
                )
                MaterialTheme(colorScheme = colors) {
                    val checked by checkedState
                    val on by enabledState
                    // Equal gap between thumb and track edge in both states.
                    val travel = TRACK_W_DP - THUMB_DP - 2 * INSET_DP
                    // One animated 0..1 position for tap, drag and release, so letting go of a
                    // drag continues from where the thumb is instead of replaying a toggle.
                    val drag by dragFraction
                    val position = remember { Animatable(if (checkedState.value) 1f else 0f) }
                    LaunchedEffect(drag, checked) {
                        val d = drag
                        if (d != null) position.snapTo(d)
                        else position.animateTo(if (checked) 1f else 0f, tween(180))
                    }
                    val thumbX = (INSET_DP + travel * position.value).dp
                    val cs = MaterialTheme.colorScheme
                    val p = position.value
                    val trackBody = lerp(cs.surfaceVariant, cs.primary, p)
                    val trackPalette = SeaGlassPalette(
                        trackBody, lerp(trackBody, Color.White, .35f), cs.onSurface
                    )
                    val thumbBody = lerp(cs.outline, lerp(cs.onPrimary, Color.White, .85f), p)
                    val thumbPalette = SeaGlassPalette(
                        thumbBody, lerp(thumbBody, Color.White, .35f), cs.onSurface
                    )
                    // The outer padding is the room the glow needs; the view itself is only
                    // track-sized (see onMeasure), so nothing around it is pushed away.
                    Box(Modifier.fillMaxSize().padding(GLOW_DP.dp).alpha(if (on) 1f else 0.45f)) {
                        SeaGlassMaterialTheme(style = SmallGlassRecipe, shape = SeaGlassShape.Capsule) {
                            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(50)).seaGlass(palette = trackPalette))
                        }
                        SeaGlassMaterialTheme(style = SmallGlassRecipe, shape = SeaGlassShape.Disc) {
                            Box(
                                Modifier.align(Alignment.CenterStart)
                                    .offset(x = thumbX)
                                    .size(THUMB_DP.dp)
                                    .clip(CircleShape)
                                    .seaGlass(palette = thumbPalette)
                            )
                        }
                    }
                }
            }
        }
        val g = (GLOW_DP * resources.displayMetrics.density).toInt()
        addView(
            glassView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
                setMargins(-g, -g, -g, -g)
            }
        )
        super.setOnClickListener { toggle() }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val d = resources.displayMetrics.density
        val w = (TRACK_W_DP * d).toInt()
        val h = (TRACK_H_DP * d).toInt()
        setMeasuredDimension(w, h)
        val g = (GLOW_DP * d).toInt()
        glassView.measure(
            MeasureSpec.makeMeasureSpec(w + 2 * g, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(h + 2 * g, MeasureSpec.EXACTLY)
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return super.onTouchEvent(event)
        val travelPx = (TRACK_W_DP - THUMB_DP - 2 * INSET_DP) * resources.displayMetrics.density
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                dragging = false
                startFraction = if (isChecked) 1f else 0f
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                if (!dragging && abs(dx) > touchSlop) {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (dragging) dragFraction.value = (startFraction + dx / travelPx).coerceIn(0f, 1f)
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    val f = dragFraction.value ?: startFraction
                    dragFraction.value = null
                    dragging = false
                    isChecked = f >= 0.5f
                } else {
                    performClick()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                dragFraction.value = null
                dragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    fun toggle() {
        isChecked = !isChecked
    }

    fun setOnCheckedChangeListener(l: ((View, Boolean) -> Unit)?) {
        listener = l
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        enabledState.value = enabled
    }

    private companion object {
        const val TRACK_W_DP = 52
        const val TRACK_H_DP = 32
        const val THUMB_DP = 24
        const val INSET_DP = 4
        const val GLOW_DP = 4
    }
}
