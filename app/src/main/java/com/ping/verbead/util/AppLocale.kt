package com.ping.verbead.util

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

/**
 * 介面語言（每個 App 的語言設定）。
 *
 * API 33 以上由系統保存並套用；API 26～32 由 AppCompat 的 AppLocalesMetadataHolderService 保存，
 * 但 Service 與覆蓋視窗不會自動跟著變，所以另外在 SharedPreferences 記一份，
 * 並用 [wrap] 包出帶語言的 Context。
 */
object AppLocale {

    /** 支援的介面語言（語言標籤）。空字串代表跟隨系統。要與 res/xml/locales_config.xml 一致。 */
    val SUPPORTED_TAGS = listOf("zh-TW", "en", "ja")

    private const val PREFS = "app_locale"
    private const val KEY_TAG = "tag"

    /** 目前選擇的語言標籤；跟隨系統時回傳空字串。 */
    fun currentTag(context: Context): String {
        val fromDelegate = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        if (fromDelegate.isNotEmpty()) return matchSupported(fromDelegate)
        if (Build.VERSION.SDK_INT >= 33) return ""
        return prefs(context).getString(KEY_TAG, "").orEmpty()
    }

    fun setTag(context: Context, tag: String) {
        prefs(context).edit().putString(KEY_TAG, tag).apply()
        AppCompatDelegate.setApplicationLocales(
            if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList()
            else LocaleListCompat.forLanguageTags(tag)
        )
    }

    /** 程序啟動時呼叫：API 32 以下讓 AppCompat 的狀態與我們保存的一致（Service 可能比任何 Activity 先啟動）。 */
    fun restore(context: Context) {
        if (Build.VERSION.SDK_INT >= 33) return
        val tag = prefs(context).getString(KEY_TAG, "").orEmpty()
        if (tag.isNotEmpty() && AppCompatDelegate.getApplicationLocales().isEmpty) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
        }
    }

    /** 給 Service 的 attachBaseContext 用：API 33 以上系統已處理，直接回傳原 Context。 */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val tag = prefs(base).getString(KEY_TAG, "").orEmpty()
        if (tag.isEmpty()) return base
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList(Locale.forLanguageTag(tag)))
        return base.createConfigurationContext(config)
    }

    private fun matchSupported(tags: String): String {
        val first = Locale.forLanguageTag(tags.substringBefore(','))
        return SUPPORTED_TAGS.firstOrNull { Locale.forLanguageTag(it) == first }
            ?: SUPPORTED_TAGS.firstOrNull { Locale.forLanguageTag(it).language == first.language }
            ?: ""
    }

    private fun prefs(context: Context) =
        context.applicationContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?: context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
