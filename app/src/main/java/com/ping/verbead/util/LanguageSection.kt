package com.ping.verbead.util

import android.content.Context
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.ping.verbead.R
import com.ping.verbead.engine.ModelConfig

/**
 * 可折疊的「語言限定設定」區塊：標題列加內容。
 *
 * 預設展開與否看使用者選的輸入語言是否在 [languageIds] 裡；使用者手動展開或收合後，
 * 這個選擇會被記住，直到下次切換輸入語言才重新依語言決定。
 */
class LanguageSection(
    private val context: Context,
    private val id: String,
    private val languageIds: Set<String>,
    header: View,
    private val content: View,
    title: String,
    hint: String,
) {
    private val chevron: ImageView = header.findViewById(R.id.iv_section_chevron)
    private val hintView: TextView = header.findViewById(R.id.tv_section_hint)

    init {
        header.findViewById<TextView>(R.id.tv_section_title).text = title
        hintView.text = hint
        header.setOnClickListener {
            HapticUtil.click(context)
            prefs(context).edit().putBoolean(key(id), !isExpanded()).apply()
            refresh()
        }
    }

    private fun isApplicable(): Boolean = ModelConfig.inputLanguage(context) in languageIds

    private fun isExpanded(): Boolean {
        val p = prefs(context)
        return if (p.contains(key(id))) p.getBoolean(key(id), false) else defaultExpanded(languageIds, ModelConfig.inputLanguage(context))
    }

    /** 依目前輸入語言與使用者的選擇更新展開狀態；不適用的語言會顯示一行說明。 */
    fun refresh() {
        val expanded = isExpanded()
        content.visibility = if (expanded) View.VISIBLE else View.GONE
        chevron.animate().cancel()
        chevron.animate().rotation(if (expanded) 180f else 0f).setDuration(150L).start()
        hintView.visibility = if (isApplicable()) View.GONE else View.VISIBLE
    }

    companion object {
        private const val PREFS = "language_sections"

        private fun key(id: String) = "expanded_$id"

        private fun prefs(context: Context) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        /** 預設展開：輸入語言屬於這個區塊適用的語言。 */
        fun defaultExpanded(languageIds: Set<String>, inputLanguage: String): Boolean =
            inputLanguage in languageIds

        /** 切換輸入語言時呼叫：清掉手動的展開與收合，讓預設重新依語言決定。 */
        fun clearOverrides(context: Context) {
            prefs(context).edit().clear().apply()
        }
    }
}
