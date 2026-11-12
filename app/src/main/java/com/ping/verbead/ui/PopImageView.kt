package com.ping.verbead.ui

import android.content.Context
import android.util.AttributeSet
import android.view.animation.OvershootInterpolator
import androidx.appcompat.widget.AppCompatImageView

/** An ImageView that pops when its icon is swapped (mic, recording, loading ...). */
class PopImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private var lastRes = 0

    override fun setImageResource(resId: Int) {
        val changed = lastRes != 0 && resId != lastRes && isAttachedToWindow && visibility == VISIBLE
        lastRes = resId
        super.setImageResource(resId)
        if (changed) {
            animate().cancel()
            scaleX = 0.55f
            scaleY = 0.55f
            alpha = 0.4f
            animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(240).setInterpolator(OvershootInterpolator(2.2f)).start()
        }
    }
}
