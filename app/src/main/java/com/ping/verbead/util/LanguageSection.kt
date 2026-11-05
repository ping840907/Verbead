package com.ping.verbead.util

import android.content.Context
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.ping.verbead.R

/**
 * 可折疊的「語言限定設定」區塊：標題列加內容。預設展開，使用者收合或展開後會被記住。
 */
class LanguageSection(
    private val context: Context,
    private val id: String,
    header: View,
    private val content: View,
    title: String,
) {
    private val chevron: ImageView = header.findViewById(R.id.iv_section_chevron)

    init {
        header.findViewById<TextView>(R.id.tv_section_title).text = title
        header.setOnClickListener {
            HapticUtil.click(context)
            prefs(context).edit().putBoolean(key(id), !isExpanded()).apply()
            refresh()
        }
    }

    private fun isExpanded(): Boolean = prefs(context).getBoolean(key(id), true)

    fun refresh() {
        val expanded = isExpanded()
        content.visibility = if (expanded) View.VISIBLE else View.GONE
        chevron.animate().cancel()
        chevron.animate().rotation(if (expanded) 180f else 0f).setDuration(150L).start()
    }

    private companion object {
        const val PREFS = "language_sections"

        fun key(id: String) = "expanded_$id"

        fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }
}
