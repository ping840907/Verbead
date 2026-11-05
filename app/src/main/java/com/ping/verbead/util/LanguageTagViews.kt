package com.ping.verbead.util

import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.ping.verbead.R
import com.ping.verbead.engine.lang.LanguageTag

/** 在 [container] 裡依序放上語言標籤小膠囊；多語言用綠色，指定語言用紫色。 */
fun bindLanguageTags(container: LinearLayout, tags: Collection<LanguageTag>) {
    container.removeAllViews()
    val context = container.context
    val density = context.resources.displayMetrics.density
    for (tag in tags) {
        val chip = TextView(context, null, 0, R.style.Verbead_Text_Badge).apply {
            text = context.getString(tag.labelRes)
            if (tag.isGeneric) {
                setBackgroundResource(R.drawable.bg_status_badge_success)
                setTextColor(ContextCompat.getColor(context, R.color.status_success_text))
            } else {
                setBackgroundResource(R.drawable.bg_status_badge_recommend)
                setTextColor(ContextCompat.getColor(context, R.color.tag_recommend))
            }
        }
        container.addView(
            chip,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = (6 * density).toInt()
            }
        )
    }
}
