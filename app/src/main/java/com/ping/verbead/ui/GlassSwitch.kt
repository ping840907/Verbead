package com.ping.verbead.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import com.ping.verbead.ui.style.UiStyle
import com.ping.verbead.ui.style.UiStyleManager
import dev.mttmcknn.seaglass.SeaGlassMaterialTheme
import dev.mttmcknn.seaglass.SeaGlassPalette
import dev.mttmcknn.seaglass.SeaGlassShape
import dev.mttmcknn.seaglass.SeaGlassStyle
import dev.mttmcknn.seaglass.rememberSeaGlassColorScheme
import dev.mttmcknn.seaglass.seaGlass
import kotlin.math.abs

/** Quiet recipe with little edge wobble, used for small controls. */
internal val SmallGlassRecipe = SeaGlassStyle.Quiet.copy(
    tumble = 0.008f,
    thickness = 4.dp,
    elevation = 0.dp,
    transmission = 0f
)

/**
 * Dual-style switch supporting both [UiStyle.EXPRESSIVE] (Material 3 Expressive morphing thumb +
 * checkmark + bouncy spring) and [UiStyle.SEA_GLASS] (dev.mttmcknn:sea-glass). Drop-in for Switch:
 * [isChecked], [setOnCheckedChangeListener], [toggle] and [setEnabled] behave identically.
 */
class GlassSwitch @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val checkedState = mutableStateOf(false)
    private val enabledState = mutableStateOf(true)
    private val heldState = mutableStateOf(false)
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
                val style = UiStyleManager.currentStyle(context)
                val dark = isSystemInDarkTheme()
                val colors = rememberSeaGlassColorScheme(
                    dynamicColor = true,
                    darkTheme = dark
                )
                MaterialTheme(colorScheme = colors) {
                    val checked by checkedState
                    val on by enabledState
                    val held by heldState
                    val drag by dragFraction
                    val cs = MaterialTheme.colorScheme

                    if (style == UiStyle.EXPRESSIVE) {
                        val position = remember { Animatable(if (checkedState.value) 1f else 0f) }
                        LaunchedEffect(drag, checked) {
                            val d = drag
                            if (d != null) {
                                position.snapTo(d)
                            } else {
                                position.animateTo(
                                    if (checked) 1f else 0f,
                                    spring(dampingRatio = 0.62f, stiffness = 480f)
                                )
                            }
                        }
                        val p = position.value.coerceIn(0f, 1f)
                        val thumbW by animateDpAsState(
                            targetValue = when {
                                held -> 28.dp
                                p >= 0.5f -> 24.dp
                                else -> 16.dp
                            },
                            animationSpec = spring(dampingRatio = 0.58f, stiffness = 540f),
                            label = "m3e-switch-thumb-w"
                        )
                        val thumbH by animateDpAsState(
                            targetValue = when {
                                held -> 22.dp
                                p >= 0.5f -> 24.dp
                                else -> 16.dp
                            },
                            animationSpec = spring(dampingRatio = 0.58f, stiffness = 540f),
                            label = "m3e-switch-thumb-h"
                        )
                        val thumbCorner by animateDpAsState(
                            targetValue = if (held) 10.dp else 12.dp,
                            animationSpec = spring(stiffness = 600f),
                            label = "m3e-switch-thumb-r"
                        )
                        val trackColor = lerp(cs.surfaceContainerHighest, cs.primary, p)
                        val borderColor = lerp(cs.outline, cs.primary, p)
                        val thumbColor = lerp(cs.outline, cs.onPrimary, p)
                        val offCenterX = (TRACK_H_DP / 2f).dp
                        val onCenterX = (TRACK_W_DP - TRACK_H_DP / 2f).dp
                        val thumbCenterX = androidx.compose.ui.unit.lerp(offCenterX, onCenterX, p)
                        val thumbX = thumbCenterX - thumbW / 2

                        Box(Modifier.fillMaxSize().padding(GLOW_DP.dp).alpha(if (on) 1f else 0.42f)) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(trackColor)
                                    .border(2.dp, borderColor, RoundedCornerShape(16.dp))
                            )
                            Box(
                                Modifier
                                    .align(Alignment.CenterStart)
                                    .offset(x = thumbX)
                                    .size(width = thumbW, height = thumbH)
                                    .clip(RoundedCornerShape(thumbCorner))
                                    .background(thumbColor)
                            ) {
                                val checkAlpha = ((p - 0.45f) / 0.55f).coerceIn(0f, 1f)
                                if (checkAlpha > 0.02f) {
                                    Canvas(
                                        Modifier
                                            .fillMaxSize()
                                            .padding(4.dp)
                                            .alpha(checkAlpha)
                                    ) {
                                        val path = androidx.compose.ui.graphics.Path().apply {
                                            moveTo(size.width * 0.16f, size.height * 0.52f)
                                            lineTo(size.width * 0.42f, size.height * 0.78f)
                                            lineTo(size.width * 0.84f, size.height * 0.24f)
                                        }
                                        drawPath(
                                            path = path,
                                            color = cs.primary,
                                            style = Stroke(
                                                width = 2.2.dp.toPx(),
                                                cap = StrokeCap.Round,
                                                join = StrokeJoin.Round
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        val travel = TRACK_W_DP - THUMB_DP - 2 * INSET_DP
                        val position = remember { Animatable(if (checkedState.value) 1f else 0f) }
                        LaunchedEffect(drag, checked) {
                            val d = drag
                            if (d != null) position.snapTo(d)
                            else position.animateTo(if (checked) 1f else 0f, tween(180))
                        }
                        val stretch = kotlin.math.sin(Math.PI * position.value.coerceIn(0f, 1f)).toFloat() * 0.3f
                        val thumbX = (INSET_DP + travel * position.value).dp
                        val p = position.value
                        val light = !dark
                        val offTrack = if (light) lerp(cs.surfaceVariant, cs.onSurface, .22f) else cs.surfaceVariant
                        val trackBody = lerp(offTrack, cs.primary, p)
                        val rim = if (light) cs.onSurface.copy(alpha = .3f) else Color.Transparent
                        val trackPalette = SeaGlassPalette(
                            trackBody, lerp(trackBody, Color.White, .35f), cs.onSurface
                        )
                        val thumbBody = lerp(cs.outline, lerp(cs.onPrimary, Color.White, .85f), p)
                        val thumbPalette = SeaGlassPalette(
                            thumbBody, lerp(thumbBody, Color.White, .35f), cs.onSurface
                        )
                        Box(Modifier.fillMaxSize().padding(GLOW_DP.dp).alpha(if (on) 1f else 0.45f)) {
                            SeaGlassMaterialTheme(style = SmallGlassRecipe, shape = SeaGlassShape.Capsule) {
                                Box(
                                    Modifier.fillMaxSize().clip(RoundedCornerShape(50)).seaGlass(palette = trackPalette)
                                        .border(1.dp, rim, RoundedCornerShape(50))
                                )
                            }
                            SeaGlassMaterialTheme(style = SmallGlassRecipe, shape = SeaGlassShape.Disc) {
                                Box(
                                    Modifier.align(Alignment.CenterStart)
                                        .offset(x = thumbX)
                                        .size(THUMB_DP.dp)
                                        .graphicsLayer {
                                            scaleX = 1f + stretch
                                            scaleY = 1f - stretch * 0.22f
                                        }
                                        .clip(CircleShape)
                                        .seaGlass(palette = thumbPalette)
                                        .border(1.dp, rim, CircleShape)
                                )
                            }
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
                heldState.value = true
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
                heldState.value = false
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
                heldState.value = false
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

    override fun performClick(): Boolean {
        toggle()
        return super.performClick()
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
